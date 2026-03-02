precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTextureY;
uniform sampler2D uTextureUV;
uniform float uTime;
uniform int uShowZebra;
void main() {
    float y = texture2D(uTextureY, vTexCoord).r;
    vec4 uv = texture2D(uTextureUV, vTexCoord);
    float u = uv.r - 0.5;
    float v = uv.a - 0.5;
    float r = y + 1.402 * v;
    float g = y - 0.34414 * u - 0.71414 * v;
    float b = y + 1.772 * u;
    vec4 color = vec4(r, g, b, 1.0);

    if (uShowZebra == 1) {
        float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
        float stripe = mod((gl_FragCoord.x - gl_FragCoord.y + uTime * 0.05), 16.0);
        if (stripe < 6.0) {
            if (luma >= 0.85) {
                color = vec4(1.0, 0.0, 0.0, 1.0);
            } else if (luma >= 0.7) {
                color = vec4(0.0, 1.0, 0.0, 1.0);
            }
        }
    }
    gl_FragColor = color;
}
