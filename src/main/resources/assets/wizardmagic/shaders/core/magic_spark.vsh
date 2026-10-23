#version 150

// 魔法光点：CPU 端已按相机朝向展开成 billboard，这里只做变换与数据传递。
// UV0 的 (0,0)~(1,1) 对应光点方块的四个角，片元里用中心距离做径向衰减。

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vColor;
out vec2 vUv;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vColor = Color;
    vUv = UV0;
}
