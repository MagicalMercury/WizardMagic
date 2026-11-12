#version 150

uniform sampler2D uTex;
uniform sampler2D uLightmap;

in vec4 vColor;
in vec2 vUv;
in vec2 vLight;

out vec4 fragColor;

void main() {
    vec4 texel = texture(uTex, vUv);
    if (texel.a < 0.05) {
        discard;
    }

    vec3 light = texture(uLightmap, vLight).rgb;

    fragColor = vec4(texel.rgb * vColor.rgb * light, texel.a * vColor.a);
}
