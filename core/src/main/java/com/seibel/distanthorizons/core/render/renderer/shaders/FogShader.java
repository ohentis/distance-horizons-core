package com.seibel.distanthorizons.core.render.renderer.shaders;

import com.seibel.distanthorizons.api.enums.rendering.EFogColorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.fog.LodFogConfig;
import com.seibel.distanthorizons.core.render.glObject.shader.Shader;
import com.seibel.distanthorizons.core.render.glObject.shader.ShaderProgram;
import com.seibel.distanthorizons.core.render.glObject.vertexAttribute.VertexAttribute;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.coreapi.util.math.Mat4f;
import com.seibel.distanthorizons.coreapi.util.math.Vec3f;
import org.lwjgl.opengl.GL32;

import java.awt.*;

public class FogShader extends AbstractShaderRenderer {
    public static FogShader INSTANCE = new FogShader(LodFogConfig.generateFogConfig());
    private static final IVersionConstants VERSION_CONSTANTS = SingletonInjector.INSTANCE.get(IVersionConstants.class);


//    public final int modelOffsetUniform;
//    public final int worldYOffsetUniform;

    public final int gProjUniform;
    public final int gDepthMapUniform;

    // Fog Uniforms
    public final int fogColorUniform;
    public final int fogScaleUniform;
    public final int fogVerticalScaleUniform;
    public final int nearFogStartUniform;
    public final int nearFogLengthUniform;;
    public final int fullFogModeUniform;

    public FogShader(LodFogConfig fogConfig) {
        // TODO & Note: This code is a bit jank, so try to make it better later (preferably not using something to process the shader)
        // This code is just a temp fix so that it looks fine for the time being
        // and even with the jank soloution, i cannot get it to work
        super(new ShaderProgram(
                () -> Shader.loadFile("shaders/normal.vert", false, new StringBuilder()).toString(),
                () -> fogConfig.loadAndProcessFragShader("shaders/fog/fog.frag", false).toString(),
                "fragColor", new String[] { "vPosition" }
        ));

//        modelOffsetUniform = this.shader.getUniformLocation("modelOffset");
//        worldYOffsetUniform = this.shader.tryGetUniformLocation("worldYOffset");

        gProjUniform = this.shader.getUniformLocation("gProj");
        gDepthMapUniform = this.shader.getUniformLocation("gDepthMap");
        // Fog uniforms
        fogColorUniform = this.shader.getUniformLocation("fogColor");
        fullFogModeUniform = this.shader.getUniformLocation("fullFogMode");
        fogScaleUniform = this.shader.tryGetUniformLocation("fogScale");
        fogVerticalScaleUniform = this.shader.tryGetUniformLocation("fogVerticalScale");
        // near
        nearFogStartUniform = this.shader.tryGetUniformLocation("nearFogStart");
        nearFogLengthUniform = this.shader.tryGetUniformLocation("nearFogLength");
    }

    @Override
    void setVertexAttributes() {
        va.setVertexAttribute(0, 0, VertexAttribute.VertexPointer.addVec2Pointer(false));
    };

    @Override
    void setShaderUniforms(float partialTicks) {
        int lodDrawDistance = RenderUtil.getFarClipPlaneDistanceInBlocks();
        int vanillaDrawDistance = MC_RENDER.getRenderDistance() * LodUtil.CHUNK_WIDTH;
//        super.bind();
        vanillaDrawDistance += 32; // Give it a 2 chunk boundary for near fog.


        Mat4f perspective = Mat4f.perspective(
                (float) MC_RENDER.getFov(partialTicks),
                MC_RENDER.getTargetFrameBufferViewportWidth() / (float) MC_RENDER.getTargetFrameBufferViewportHeight(),
                RenderUtil.getNearClipPlaneDistanceInBlocks(partialTicks),
                (float) ((lodDrawDistance + LodUtil.REGION_WIDTH) * Math.sqrt(2)));



//        if (worldYOffsetUniform != -1) this.shader.setUniform(worldYOffsetUniform, (float) MC.getWrappedClientWorld().getMinHeight());


        this.shader.setUniform(this.shader.getUniformLocation("gProj"), perspective);
        GL32.glUniform1i(gDepthMapUniform, 0);
        // Fog
        this.shader.setUniform(fullFogModeUniform, MC_RENDER.isFogStateSpecial() ? 1 : 0);
        this.shader.setUniform(fogColorUniform, MC_RENDER.isFogStateSpecial() ? getSpecialFogColor(partialTicks) : getFogColor(partialTicks));

        float nearFogLen = vanillaDrawDistance * 0.2f / lodDrawDistance;
        float nearFogStart = vanillaDrawDistance * (VERSION_CONSTANTS.isVanillaRenderedChunkSquare() ? (float)Math.sqrt(2.) : 1.f) / lodDrawDistance;
        if (nearFogStartUniform != -1) this.shader.setUniform(nearFogStartUniform, nearFogStart);
        if (nearFogLengthUniform != -1) this.shader.setUniform(nearFogLengthUniform, nearFogLen);
        if (fogScaleUniform != -1) this.shader.setUniform(fogScaleUniform, 1.f/lodDrawDistance);
        if (fogVerticalScaleUniform != -1) this.shader.setUniform(fogVerticalScaleUniform, 1.f/MC.getWrappedClientWorld().getHeight());
    }



    private Color getFogColor(float partialTicks)
    {
        Color fogColor;

        if (Config.Client.Advanced.Graphics.Fog.colorMode.get() == EFogColorMode.USE_SKY_COLOR)
            fogColor = MC_RENDER.getSkyColor();
        else
            fogColor = MC_RENDER.getFogColor(partialTicks);

        return fogColor;
    }
    private Color getSpecialFogColor(float partialTicks)
    {
        return MC_RENDER.getSpecialFogColor(partialTicks);
    }

    public void setModelPos(Vec3f modelPos) {
//        this.shader.setUniform(modelOffsetUniform, modelPos);
    }
}
