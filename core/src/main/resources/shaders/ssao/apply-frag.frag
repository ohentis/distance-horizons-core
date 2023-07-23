#version 150 core

in vec2 TexCoord;
in vec2 ViewRay;

out vec4 fragColor;

uniform sampler2D gSSAOMap;

void main()
{
   fragColor = vec4(0.0, 0.0, 0.0, 1-texture(gSSAOMap, TexCoord).r);
}