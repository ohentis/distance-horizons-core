#version 150 core

uniform vec4 uColor;
uniform int uSkyLight;
uniform int uBlockLight;
uniform sampler2D uLightMap;

out vec4 fragColor;

void main()
{
    float blockLight = (float(uBlockLight)+0.5) / 16.0;
    float skyLight = (float(uSkyLight)+0.5) / 16.0;
    vec4 lightColor = vec4(texture(uLightMap, vec2(blockLight, skyLight)).xyz, 1.0);
    
    fragColor = lightColor * uColor;
}