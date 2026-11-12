package com.wizardmagic.common.spell;

import com.wizardmagic.core.spell.script.SpellBytecode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 存档里的法术目录。
 *
 * <pre>
 * &lt;存档&gt;/wizard_magic/
 *   spell/&lt;id&gt;.spell     玩家自定义法术（源码，权威）
 *   .cache/&lt;id&gt;.spellc   编译产物（预设 + 玩家法术共用），派生数据，删了会自动重建
 * </pre>
 *
 * <p>为什么缓存落在存档而不是资源包：法术 id 与世界的玩法绑定，换世界就该换一套；
 * 而且玩家法术本来就只在这个存档里有意义。预设法术也写一份进来，是为了让
 * 「这个存档里的法术编译成了什么」看得见、能核对——不是一个纯加速结构
 * （解析 + 代码生成一共才 ~30 µs，一次 3000 粒的步进是它的 40 倍）。</p>
 *
 * <p>这里只管路径与文件 IO，编译逻辑在 {@link SpellScripts}。</p>
 */
public final class SpellStorage
{
    private static final String ROOT_DIR = "wizard_magic";
    private static final String SPELL_DIR = "spell";
    private static final String CACHE_DIR = ".cache";
    private static final String README = "README.txt";

    private static final String README_TEXT = String.join("\n",
            "wizard_magic —— 法术脚本目录",
            "",
            "spell/    放你自己写的 .spell 文件，一个法术一个文件。",
            "          文件名（去掉 .spell）就是法术 id，例如 firestorm.spell → /spell run firestorm。",
            "          同名会覆盖内置预设；编译失败时会自动退回内置版本并在日志里提示。",
            "",
            ".cache/   游戏自动生成的编译产物（.spellc），预设和你的法术都有。",
            "          删掉没有任何影响，下次启动/加载会重新编译并写回。",
            "          文件头带 IR 版本、源码哈希和变量签名，改过源码或换过模组版本后会自动作废。",
            "");

    private SpellStorage()
    {
    }

    // ---------------------------------------------------------------- 路径

    public static Path root(MinecraftServer server)
    {
        return server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR);
    }

    public static Path spellDir(MinecraftServer server)
    {
        return root(server).resolve(SPELL_DIR);
    }

    public static Path cacheDir(MinecraftServer server)
    {
        return root(server).resolve(CACHE_DIR);
    }

    /** 建目录 + 铺一份说明；已经存在就不动 */
    public static void ensure(MinecraftServer server) throws IOException
    {
        Files.createDirectories(spellDir(server));
        Files.createDirectories(cacheDir(server));
        Path readme = root(server).resolve(README);
        if (!Files.exists(readme))
        {
            Files.writeString(readme, README_TEXT, StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- 玩家法术

    /** 法术 id 只允许这套字符：既能当文件名，也挡住 {@code ../} 之类的路径穿越 */
    public static boolean isSafeId(String id)
    {
        if (id == null || id.isEmpty() || id.length() > 64 || id.startsWith("."))
        {
            return false;
        }
        for (int i = 0; i < id.length(); i++)
        {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok)
            {
                return false;
            }
        }
        return true;
    }

    /** 目录里所有玩家法术 id（按文件名排序） */
    public static List<String> playerIds(MinecraftServer server)
    {
        List<String> out = new ArrayList<>();
        Path dir = spellDir(server);
        if (!Files.isDirectory(dir))
        {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.spell"))
        {
            for (Path p : stream)
            {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - ".spell".length());
                if (isSafeId(id))
                {
                    out.add(id);
                }
            }
        }
        catch (IOException ignored)
        {
            // 目录读不动就当没有玩家法术，不影响预设
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** @return null = 这个 id 没有玩家文件 */
    public static String readPlayerSource(MinecraftServer server, String id)
    {
        if (!isSafeId(id))
        {
            return null;
        }
        Path file = spellDir(server).resolve(id + ".spell");
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        try
        {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    public static Path playerFile(MinecraftServer server, String id)
    {
        return spellDir(server).resolve(id + ".spell");
    }

    // ---------------------------------------------------------------- 编译产物

    public static Path cacheFile(MinecraftServer server, String id)
    {
        return cacheDir(server).resolve(id + "." + SpellBytecode.EXTENSION);
    }

    public static byte[] readCache(MinecraftServer server, String id)
    {
        if (!isSafeId(id))
        {
            return null;
        }
        Path file = cacheFile(server, id);
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        try
        {
            return Files.readAllBytes(file);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** 写不进去只影响下次启动要重编，不抛给调用方 */
    public static boolean writeCache(MinecraftServer server, String id, byte[] blob)
    {
        if (!isSafeId(id) || blob == null)
        {
            return false;
        }
        try
        {
            Files.createDirectories(cacheDir(server));
            Files.write(cacheFile(server, id), blob);
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public static void deleteCache(MinecraftServer server, String id)
    {
        if (!isSafeId(id))
        {
            return;
        }
        try
        {
            Files.deleteIfExists(cacheFile(server, id));
        }
        catch (IOException ignored)
        {
            // 删不掉就算了，反正加载时会按哈希重新判断
        }
    }

    /** 诊断用：缓存文件大小，没有则 -1 */
    public static long cacheSize(MinecraftServer server, String id)
    {
        try
        {
            Path file = cacheFile(server, id);
            return Files.isRegularFile(file) ? Files.size(file) : -1L;
        }
        catch (IOException e)
        {
            return -1L;
        }
    }

    /** 把目录树写成一行给命令看 */
    public static String describe(MinecraftServer server)
    {
        return String.format(Locale.ROOT, "%s（玩家 %d 个法术，缓存 %d 个文件）",
                root(server).toAbsolutePath(), playerIds(server).size(), countCache(server));
    }

    private static long countCache(MinecraftServer server)
    {
        Path dir = cacheDir(server);
        if (!Files.isDirectory(dir))
        {
            return 0L;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*." + SpellBytecode.EXTENSION))
        {
            long n = 0;
            for (Path ignored : stream)
            {
                n++;
            }
            return n;
        }
        catch (IOException e)
        {
            return 0L;
        }
    }
}
