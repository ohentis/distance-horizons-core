#version 150 core

in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D gDhColorTexture;



void main()
{
    fragColor = texture(gDhColorTexture, TexCoord);
}
