#version 150

in vec3 aPos;      // 世界坐标
in vec2 aUv;
in vec4 aColor;
in vec2 aLight;    // lightmap UV（0..1）

uniform mat4 uProj;

out vec4 vColor;
out vec2 vUv;
out vec2 vLight;

void main() {
    // aPos 已经是「视图空间坐标」：
    //   LevelRenderer 在 renderLevel 开头做过 poseStack.mulPoseMatrix(视图旋转)，
    //   BlockEntityRenderDispatcher 又做了 translate(pos - cameraPos)。
    // 所以顶点到这里时旋转和平移都已就位，这里只做投影——
    // 再加一次视图旋转会重复旋转（表现为液面浮空、倾斜）。
    // 刻意不做方向光照：液面是水平面，方向光只会让它随视角忽明忽暗。
    gl_Position = uProj * vec4(aPos, 1.0);

    vColor = aColor;
    vUv = aUv;
    vLight = aLight;
}
