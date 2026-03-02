#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTextureRGBA;
uniform float uTime;
uniform int uShowZebra;
void main() {
    vec4 color = texture(uTextureRGBA, vTexCoord);
    if (uShowZebra == 1) {
        float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
        float stripe = mod((gl_FragCoord.x - gl_FragCoord.y + uTime * 0.05), 16.0);
        if (stripe < 6.0) {
            if (luma >= 0.85) {
                color = vec4(1.0, 0.0, 0.0, 1.0);
            } else if (luma >= 0.8) {
                color = vec4(0.0, 1.0, 0.0, 1.0);
            }
        }
    }
    fragColor = color;
}
