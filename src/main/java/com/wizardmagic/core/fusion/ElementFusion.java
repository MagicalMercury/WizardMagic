package com.wizardmagic.core.fusion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 元素合成表：只负责「两个元素能合成什么」，不碰元素场、方块或任何游戏状态。
 *
 * <p>数据来自 {@code data/wizardmagic/fusion/elements.json}。规则：</p>
 * <ul>
 *   <li>0 阶（地水火风）没有配方；</li>
 *   <li>任一配方的产物阶数 = max(输入 A 阶, 输入 B 阶) + 1，因此合成严格升阶；</li>
 *   <li>合成无序：{@code A + B} 与 {@code B + A} 是同一条；</li>
 *   <li>严格升阶 ⇒ 配方图天然无环（本类在加载时校验阶数，不额外做 DFS）。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 * ElementFusion fusion = ElementFusion.get();
 * fusion.combine("fire", "air").ifPresent(e -> ...);   // 光
 * fusion.combine("earth", "water").ifPresent(e -> ...); // 暗
 * fusion.combine("light", "light");                     // 不同元素不可自合 → empty
 * }</pre>
 */
public final class ElementFusion {

    /** 内置数据表位置。 */
    public static final String RESOURCE = "/data/wizardmagic/fusion/elements.json";
    /** 当前数据格式标识与版本。 */
    public static final String FORMAT = "wizardmagic:element_fusion";
    public static final int VERSION = 1;

    private static ElementFusion instance;

    private final List<FusionElement> elements;
    private final Map<String, FusionElement> byId;
    private final Map<String, FusionElement> byPair;
    private final List<String> issues;

    private ElementFusion(List<FusionElement> elements,
                          Map<String, FusionElement> byId,
                          Map<String, FusionElement> byPair,
                          List<String> issues) {
        this.elements = elements;
        this.byId = byId;
        this.byPair = byPair;
        this.issues = issues;
    }

    /** 懒加载内置表；加载失败时返回空表并记录问题，不抛异常。 */
    public static synchronized ElementFusion get() {
        if (instance == null) {
            instance = loadBundled();
        }
        return instance;
    }

    /** 测试/重载用：丢弃缓存，下次 {@link #get()} 重新读取资源。 */
    public static synchronized void reload() {
        instance = null;
    }

    private static ElementFusion loadBundled() {
        try (InputStream in = ElementFusion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return new ElementFusion(List.of(), Map.of(), Map.of(),
                        List.of("找不到资源 " + RESOURCE));
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ElementFusion(List.of(), Map.of(), Map.of(),
                    List.of("读取 " + RESOURCE + " 失败: " + e));
        }
    }
    public static ElementFusion parse(String json) {
        List<String> issues = new ArrayList<>();
        List<FusionElement> elements = new ArrayList<>();
        Map<String, FusionElement> byId = new LinkedHashMap<>();
        Map<String, FusionElement> byPair = new LinkedHashMap<>();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String format = root.has("format") ? root.get("format").getAsString() : "";
        if (!FORMAT.equals(format)) {
            issues.add("format 应为 " + FORMAT + "，实际 \"" + format + "\"");
        }
        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if (version > VERSION) {
            issues.add("数据版本 " + version + " 高于本模组支持的版本 " + VERSION);
        }

        for (JsonElement el : root.getAsJsonArray("elements")) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            int tier = o.get("tier").getAsInt();
            List<String> recipe = null;
            if (o.has("recipe") && !o.get("recipe").isJsonNull()) {
                recipe = new ArrayList<>();
                for (JsonElement r : o.getAsJsonArray("recipe")) {
                    recipe.add(r.getAsString());
                }
            }
            if (byId.containsKey(id)) {
                issues.add("重复元素 id: " + id);
                continue;
            }
            FusionElement e = new FusionElement(id, tier, recipe);
            elements.add(e);
            byId.put(id, e);
        }

        for (FusionElement e : elements) {
            if (e.isBase()) {
                if (e.tier() != 0) {
                    issues.add(e.id() + " 是基础元素但 tier=" + e.tier() + "（应为 0）");
                }
                continue;
            }
            if (e.recipe().size() != 2) {
                issues.add(e.id() + " 配方应有 2 个输入，实际 " + e.recipe().size());
                continue;
            }
            String a = e.recipe().get(0);
            String b = e.recipe().get(1);
            FusionElement ea = byId.get(a);
            FusionElement eb = byId.get(b);
            if (ea == null) {
                issues.add(e.id() + " 引用了不存在的元素 " + a);
                continue;
            }
            if (eb == null) {
                issues.add(e.id() + " 引用了不存在的元素 " + b);
                continue;
            }
            if (a.equals(b)) {
                issues.add(e.id() + " 用了同一个元素两次（" + a + "）");
            }
            int expect = Math.max(ea.tier(), eb.tier()) + 1;
            if (e.tier() != expect) {
                issues.add(e.id() + " tier=" + e.tier() + "，但 max(" + ea.tier() + ","
                        + eb.tier() + ")+1=" + expect);
            }
            String key = FusionElement.pairKey(a, b);
            FusionElement prev = byPair.putIfAbsent(key, e);
            if (prev != null) {
                issues.add("配方冲突 " + key + " → 同时指向 " + prev.id() + " 与 " + e.id());
            }
        }

        return new ElementFusion(List.copyOf(elements), Map.copyOf(byId), Map.copyOf(byPair), List.copyOf(issues));
    }

    public Optional<FusionElement> element(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** 合成查询（无序）。任一 id 不存在则返回空。 */
    public Optional<FusionElement> combine(String a, String b) {
        if (a == null || b == null) {
            return Optional.empty();
        }
        if (!byId.containsKey(a) || !byId.containsKey(b)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPair.get(FusionElement.pairKey(a, b)));
    }

    public Optional<FusionElement> combine(FusionElement a, FusionElement b) {
        if (a == null || b == null) {
            return Optional.empty();
        }
        return combine(a.id(), b.id());
    }

    /** 按 JSON 顺序的全部元素。 */
    public List<FusionElement> elements() {
        return elements;
    }

    /** 加载期发现的问题（空表示表合法）。 */
    public List<String> issues() {
        return issues;
    }

    public boolean isValid() {
        return issues.isEmpty();
    }

    public int size() {
        return elements.size();
    }

    /** 每一阶的元素个数。 */
    public Map<Integer, Integer> tierCounts() {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (FusionElement e : elements) {
            counts.merge(e.tier(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}
