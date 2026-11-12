package com.wizardmagic.common.spell;

import com.wizardmagic.WizardMagic;
import com.wizardmagic.core.spell.script.ScriptException;
import com.wizardmagic.core.spell.script.SpellScript;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 法术脚本的加载、编译与落盘缓存。
 *
 * <p>取源码的顺序：<b>存档里的玩家法术优先</b>（{@code wizard_magic/spell/<id>.spell}），
 * 没有才回落到资源包里的内置脚本（{@code data/wizardmagic/spells/<id>.spell}）。
 * 玩家文件编译失败不会把预设弄坏——打一条警告后退回内置版本。</p>
 *
 * <p>编译产物写到 {@code wizard_magic/.cache/<id>.spellc}（见 {@link SpellStorage}）。
 * 加载时先读缓存，源码哈希 / IR 版本 / 信封校验和任意一条对不上就重新编译并覆盖。</p>
 *
 * <p>客户端不经过这个类：生成包里直接带源码，客户端自己编译，
 * 这样玩家脚本不需要客户端也装同一个文件。</p>
 */
public final class SpellScripts
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WizardMagic.MODID + "/spell");

    private static final String[] BUILT_IN = {"firestorm", "frost_nova", "flame_pillar"};

    private static final Map<String, SpellScript> CACHE = new HashMap<>();

    /** 最近一轮加载里缓存命中/重编的次数（命令与启动日志用） */
    private static int hits;
    private static int rewrites;
    private static boolean storageReady;

    private SpellScripts()
    {
    }

    /**
     * @throws ScriptException 找不到源码或编译失败
     */
    public static SpellScript load(String id)
    {
        SpellScript cached = CACHE.get(id);
        if (cached != null)
        {
            return cached;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            throw new ScriptException("服务端未就绪，无法读取脚本", 1, 1);
        }

        SpellScript script = compilePlayer(server, id);
        if (script == null)
        {
            script = SpellScript.compile(id, readBuiltIn(server, id));
        }
        attachCache(server, id, script);
        CACHE.put(id, script);
        return script;
    }

    /** 玩家法术优先；编译不过就返回 null（调用方回落内置） */
    private static SpellScript compilePlayer(MinecraftServer server, String id)
    {
        String source = SpellStorage.readPlayerSource(server, id);
        if (source == null)
        {
            return null;
        }
        try
        {
            return SpellScript.compile(id, source);
        }
        catch (ScriptException e)
        {
            LOGGER.warn("[wizardmagic] 存档法术 {} 编译失败，回退到内置版本：{}", id, e.display());
            return null;
        }
    }

    /** 磁盘上有编译产物就直接用，省掉代码生成；否则编一次并写回 */
    private static void attachCache(MinecraftServer server, String id, SpellScript script)
    {
        byte[] blob = SpellStorage.readCache(server, id);
        if (blob != null && script.adoptFastBytecode(blob))
        {
            hits++;
            return;
        }
        if (!script.prepareFastPath(Map.of()))
        {
            // 走解释器的脚本没有可缓存的指令流；顺手清掉可能存在的旧产物
            SpellStorage.deleteCache(server, id);
            return;
        }
        byte[] packed = script.fastBytecode();
        if (packed != null && SpellStorage.writeCache(server, id, packed))
        {
            rewrites++;
        }
    }

    private static String readBuiltIn(MinecraftServer server, String id)
    {
        if (!SpellStorage.isSafeId(id))
        {
            throw new ScriptException("非法法术 id: " + id, 1, 1);
        }
        ResourceLocation rl = new ResourceLocation(WizardMagic.MODID, "spells/" + id + ".spell");
        String source;
        try (InputStream in = server.getResourceManager().getResource(rl)
                .orElseThrow(() -> new IOException("资源不存在")).open())
        {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new ScriptException("读不到 " + rl + "：" + e.getMessage(), 1, 1);
        }
        return source;
    }

    // ---------------------------------------------------------------- 启动预热

    /**
     * 服务端启动时把「内置 + 存档里」的法术全编一遍，顺带把 {@code .cache} 铺好。
     *
     * <p>单个法术失败不影响其它法术：坏文件只在日志里留一行。</p>
     *
     * @return 一行摘要，给启动日志和命令用
     */
    public static String warmUp(MinecraftServer server)
    {
        hits = 0;
        rewrites = 0;
        CACHE.clear();
        if (!storageReady)
        {
            try
            {
                SpellStorage.ensure(server);
                storageReady = true;
            }
            catch (IOException e)
            {
                LOGGER.warn("[wizardmagic] 建不出存档法术目录：{}", e.getMessage());
            }
        }

        int ok = 0;
        int failed = 0;
        for (String id : allIds(server))
        {
            try
            {
                load(id);
                ok++;
            }
            catch (ScriptException e)
            {
                failed++;
                LOGGER.warn("[wizardmagic] 法术 {} 编译失败：{}", id, e.display());
            }
        }
        String summary = String.format(Locale.ROOT,
                "法术 %d 个（缓存命中 %d，重编 %d）%s；目录 %s",
                ok, hits, rewrites, failed == 0 ? "" : "，失败 " + failed, SpellStorage.describe(server));
        LOGGER.info("[wizardmagic] {}", summary);
        return summary;
    }

    /** 清掉内存缓存；下次 {@link #load} 会重新读源码与缓存文件 */
    public static void reload()
    {
        CACHE.clear();
        hits = 0;
        rewrites = 0;
    }

    public static int cacheHits()
    {
        return hits;
    }

    public static int cacheRewrites()
    {
        return rewrites;
    }

    // ---------------------------------------------------------------- 列表

    public static List<String> builtInIds()
    {
        return List.of(BUILT_IN);
    }

    /** 内置 + 存档里的玩家法术 + 本轮已加载过的 id */
    public static List<String> allIds(MinecraftServer server)
    {
        Set<String> out = new LinkedHashSet<>();
        for (String id : BUILT_IN)
        {
            out.add(id);
        }
        if (server != null)
        {
            out.addAll(SpellStorage.playerIds(server));
        }
        out.addAll(CACHE.keySet());
        return new ArrayList<>(out);
    }

    /** 命令补全：内置 id + 存档里的法术 + 已成功编译过的 id */
    public static List<String> knownIds()
    {
        return allIds(ServerLifecycleHooks.getCurrentServer());
    }

    /** 这个 id 的源码来自存档还是资源包 */
    public static boolean isPlayerSpell(String id)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && SpellStorage.readPlayerSource(server, id) != null;
    }

    /** 诊断：缓存文件大小，没有则 -1 */
    public static long cacheSize(String id)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? -1L : SpellStorage.cacheSize(server, id);
    }
}
