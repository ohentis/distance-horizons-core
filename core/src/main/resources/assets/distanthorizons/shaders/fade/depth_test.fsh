#version 150 core

in vec2 TexCoord;
in vec4 fColor;

out vec4 fragColor;

uniform sampler2D uDhDepthTexture;

// DH fade frag test
void main()
{
    float dhFragmentDepth = texture(uDhDepthTexture, TexCoord).r;
    if (dhFragmentDepth == 1)
    {
        // no MC depth
        fragColor = fColor;
    }
    else
    {
        // MC depth drawn
        fragColor = vec4(1, 1, 1, 1); // white
    }
}