#version 150
in vec3 aPos;
out vec2 vUv;
void main() {
    vUv = aPos.xy;
    gl_Position = vec4(aPos.xy * 2.0 - 1.0, 0.0, 1.0);
}
