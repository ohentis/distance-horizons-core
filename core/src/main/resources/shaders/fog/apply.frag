#version 150 core

in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D gColorTexture;
uniform sampler2D gDepthTexture;



void main()
{
    fragColor = vec4(1.0);
    
    float fragmentDepth = textureLod(gDepthTexture, TexCoord, 0).r;

    // a fragment depth of "1" means the fragment wasn't drawn to,
    // only update fragments that were drawn to
    if (fragmentDepth != 1) 
    {
        fragColor = texture(gColorTexture, TexCoord);
    }
}
