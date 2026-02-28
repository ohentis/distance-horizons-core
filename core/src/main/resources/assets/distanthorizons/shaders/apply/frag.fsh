#version 150 core

in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D uDhColorTexture;
uniform sampler2D uDhDepthTexture;

// DH apply frag
void main()
{
    //fragColor = texture(uApplyTexture, TexCoord);

    fragColor = vec4(0.0);

    // a fragment depth of "1" means the fragment wasn't drawn to,
    // only update fragments that were drawn to
    float fragmentDepth = texture(uDhDepthTexture, TexCoord).r;
    if (fragmentDepth != 1)
    {
        fragColor = texture(uDhColorTexture, TexCoord);
    }
    else
    {
        // use the original MC texture if no LODs were drawn to this fragment
        discard;
    }
}