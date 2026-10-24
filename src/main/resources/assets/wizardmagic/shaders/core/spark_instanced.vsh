#version 150

in vec2 aPos;      // 单位 quad 角: (-1,-1) .. (1,1)
in vec2 aUv;
in vec3 iPos;      // 实例: 本 tick 世界坐标
in vec3 iPrev;     // 实例: 上一 tick 世界坐标
in vec4 iColor;    // 实例: rgba
in vec4 iMeta;     // 实例: x=size, y=birthTick, z=life, w=phase

uniform mat4 uProj;
uniform mat3 uViewRot;
uniform vec3 uCamPos;
uniform float uTime;     // tick + partialTick
uniform float uPartial;  // 0..1，两个 tick 之间的插值因子
uniform float uMaxDist;      // 超过这个距离直接丢弃
uniform float uFadeStart;    // 从这里开始向 uMaxDist 淡出
uniform float uScreenScale;  // screenHeight / (2*tan(fov/2))，用于像素尺寸判断
uniform vec2 uHalfRes;       // 渲染目标像素尺寸（半分辨率 FBO，全屏回退时为屏幕尺寸）
uniform float uRefScale;     // 分流参考尺子：半分辨率 screenScale（两趟共用，见 ph）
uniform float uTinyPass;     // -1=不分趟（全屏回退），0=半分辨率大粒子趟，1=全分辨率小粒子趟

out vec4 vColor;
out vec2 vUv;
out float vPhase;

void main() {
    float t = clamp((uTime - iMeta.y) / max(iMeta.z, 1.0), 0.0, 1.0);
    float alpha = (1.0 - t) * (1.0 - t);
    float size = iMeta.x * (1.0 - 0.55 * t);

    vColor = vec4(iColor.rgb, iColor.a * alpha);
    vUv = aUv;
    vPhase = iMeta.w;

    if (alpha <= 0.002) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }

    // 渲染帧与逻辑 tick 不同步：在两个 tick 的位置之间插值
    vec3 world = mix(iPrev, iPos, uPartial);
    float dist = length(world - uCamPos);

    // 距离上限 + 淡出
    if (dist > uMaxDist) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    float fade = 1.0 - smoothstep(uFadeStart, uMaxDist, dist);

    // 本趟渲染目标下的投影半径（像素）：最小覆盖与纹素吸附都以它为准
    float pixels = size * uScreenScale / max(dist, 0.05);
    // 参考半径（以半分辨率屏幕为基准）：两趟共用同一把尺子做分流与交叉淡化
    float ph = size * uRefScale / max(dist, 0.05);

    // 分趟：小粒子（ph<4）走全分辨率趟，大粒子（ph>=3）走半分辨率趟。
    // 半分辨率网格里纹素吸附的步进是 2 个全分辨率像素，小粒子运动会
    // 「一卡一卡」；全分辨率网格下同样机制只步进 1px，不可感知，且小
    // 粒子片元数少、全分辨率开销可忽略。ph=3~4 之间两趟按权重交叉淡化
    // （贡献 ∝ alpha²，alpha 乘 sqrt(w)，两趟平方和恒为 1，总量不跳变）。
    float w = 1.0;
    if (uTinyPass > -0.5) {
        if (uTinyPass > 0.5) {
            if (ph >= 4.0) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                return;
            }
            w = 1.0 - smoothstep(3.0, 4.0, ph);
        } else {
            if (ph < 3.0) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                return;
            }
            w = smoothstep(3.0, 4.0, ph);
        }
    }
    vColor.a *= sqrt(w);

    // 最小覆盖 + 连续淡出 + 纹素吸附，只对小趟与不分趟回退生效。
    // 大趟粒子 >=3px，多纹素平均后相位差很小，不做任何处理，
    // 观感与原全分辨率管线一致。
    bool tiny = uTinyPass > 0.5 || uTinyPass < -0.5;
    if (tiny) {
        // 最小屏幕覆盖：至少 1.25 个像素的半径。面积 ∝ scale²，
        // 片元是预乘色且混合 SRC_ALPHA,ONE（贡献 ∝ alpha²），
        // alpha /= scale 保持总能量不变。
        float minPx = 1.25;
        float scale = max(1.0, minPx / max(pixels, 1.0E-4));
        size *= scale;
        vColor.a /= scale;

        // 半径不足 1 像素的光点连续淡出，跨越阈值不再整颗 pop
        if (pixels < 1.0) {
            vColor.a *= smoothstep(0.0, 1.0, pixels);
        }
    }

    vColor.a *= fade;

    // 路线 A: 视图空间坐标 = R * (p - C)
    vec3 view = uViewRot * (world - uCamPos);

    // 纹素吸附：body 剖面在中心处斜率非零，能量集中在中心纹素——
    // 中心落在纹素中心与落在纹素角点，总亮度差约 16 倍。把投影中心
    // 吸附到所在纹素的中心，小光点恒处于同一稳定相位，帧间能量恒定。
    // 全分辨率网格下吸附步进仅 1px，肉眼不可见。
    vec4 center = uProj * vec4(view, 1.0);
    vec2 off = vec2(0.0);
    if (tiny && pixels < 2.5) {
        vec2 ndc = center.xy / center.w;
        vec2 tex = (ndc * 0.5 + 0.5) * uHalfRes;
        vec2 snapped = ((floor(tex) + 0.5) / uHalfRes) * 2.0 - 1.0;
        off = snapped - ndc;
    }

    // billboard 四角深度相同（w 相同），NDC 偏移统一叠加到每个角上
    vec3 pos = view + vec3(aPos * size, 0.0);
    vec4 corner = uProj * vec4(pos, 1.0);
    gl_Position = corner + vec4(off * corner.w, 0.0, 0.0);
}
