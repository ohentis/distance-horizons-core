#version 150 core

in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D uColorTexture;



void main()
{
    fragColor = texture(uColorTexture, TexCoord);
}
