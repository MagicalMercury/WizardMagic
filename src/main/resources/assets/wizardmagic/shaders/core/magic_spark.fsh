#version 150

// 程序化光点，不依赖贴图：
//   body   柔和径向衰减（光晕）
//   core   中心小范围过曝成白（发光核心）
//   flicker 随时间与位置轻微闪烁，避免一堆光点看起来像静止贴图
// 加色混合由 shader json 的 blend 段负责（srcAlpha, one）。

in vec4 vColor;
in vec2 vUv;

uniform float Time;
uniform vec4 ColorModulator;

out vec4 fragColor;

void main() {
    // 以 quad 中心为原点，d = 0 在中心、1 在边缘
    float d = length(vUv - vec2(0.5)) * 2.0;
    if (d > 1.0) {
        discard;
    }

    float body = pow(max(1.0 - d, 0.0), 2.5);
    float core = smoothstep(0.42, 0.0, d);

    // 闪烁相位用 uv 编码，让每个光点有独立的节奏
    float phase = vUv.x * 6.2831 + vUv.y * 3.1415;
    float flicker = 0.86 + 0.14 * sin(Time + phase);

    vec3 tint = mix(vColor.rgb, vec3(1.0), core * 0.55);
    vec3 rgb = tint * (body * 1.35 + core * 1.15) * flicker;
    float alpha = clamp(body * vColor.a, 0.0, 1.0);

    fragColor = vec4(rgb * alpha * ColorModulator.rgb, alpha * ColorModulator.a);
}
