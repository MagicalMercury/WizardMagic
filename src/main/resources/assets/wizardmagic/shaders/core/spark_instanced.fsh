#version 150

in vec4 vColor;
in vec2 vUv;
in float vPhase;

uniform float uTime;

out vec4 fragColor;

void main() {
    float d = length(vUv - vec2(0.5)) * 2.0;
    if (d > 1.0) {
        discard;
    }

    float body = pow(max(1.0 - d, 0.0), 2.5);
    float core = smoothstep(0.42, 0.0, d);
    float flicker = 0.86 + 0.14 * sin(uTime * 0.12 + vPhase);

    vec3 tint = mix(vColor.rgb, vec3(1.0), core * 0.55);
    vec3 rgb = tint * (body * 1.35 + core * 1.15) * flicker;
    float alpha = clamp(body * vColor.a, 0.0, 1.0);

    fragColor = vec4(rgb * alpha, alpha);
}
