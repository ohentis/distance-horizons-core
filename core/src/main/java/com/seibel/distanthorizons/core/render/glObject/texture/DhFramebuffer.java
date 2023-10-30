package com.seibel.distanthorizons.core.render.glObject.texture;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

// TODO lowercase
public class DhFramebuffer
{
	private final Int2IntMap attachments;
	private final int maxDrawBuffers;
	private final int maxColorAttachments;
	private boolean hasDepthAttachment;
	private int id;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DhFramebuffer() 
	{
		this.id = GL43C.glGenFramebuffers();

		this.attachments = new Int2IntArrayMap();
		this.maxDrawBuffers = GL43C.glGetInteger(GL30C.GL_MAX_DRAW_BUFFERS);
		this.maxColorAttachments = GL43C.glGetInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS);
		this.hasDepthAttachment = false;
	}

	/** For internal use by Iris, do not remove. */
	public DhFramebuffer(int id) 
	{
		this.id = id;
		
		this.attachments = new Int2IntArrayMap();
		this.maxDrawBuffers = GL43C.glGetInteger(GL30C.GL_MAX_DRAW_BUFFERS);
		this.maxColorAttachments = GL43C.glGetInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS);
		this.hasDepthAttachment = false;
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	public void addDepthAttachment(int texture, EDhDepthBufferFormat depthBufferFormat) 
	{
		bind();
		
		if (depthBufferFormat.isCombinedStencil())
		{
			GL43C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture, 0);
		}
		else
		{
			GL43C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture, 0);
		}

		this.hasDepthAttachment = true;
	}

	public void addColorAttachment(int index, int texture)
	{
		int fb = id;
		bind();
		
		GL43C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + index, GL30C.GL_TEXTURE_2D, texture, 0);
		attachments.put(index, texture);
	}

	public void noDrawBuffers()
	{
		bind(); 
		GL43C.glDrawBuffers(new int[]{GL30C.GL_NONE});
	}
	
	public void drawBuffers(int[] buffers)
	{
		int[] glBuffers = new int[buffers.length]; int index = 0;
		
		if (buffers.length > maxDrawBuffers)
		{
			throw new IllegalArgumentException("Cannot write to more than " + maxDrawBuffers + " draw buffers on this GPU");
		}
		
		for (int buffer : buffers)
		{
			if (buffer >= maxColorAttachments)
			{
				throw new IllegalArgumentException("Only " + maxColorAttachments + " color attachments are supported on this GPU, but an attempt was made to write to a color attachment with index " + buffer);
			}
			
			glBuffers[index++] = GL30C.GL_COLOR_ATTACHMENT0 + buffer;
		}
		
		bind(); 
		GL43C.glDrawBuffers(new int[]{GL30C.GL_NONE});
	}
	
	public void readBuffer(int buffer)
	{
		bind();
		GL43C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0 + buffer);
	}
	
	public int getColorAttachment(int index) { return attachments.get(index); }
	
	public boolean hasDepthAttachment() { return hasDepthAttachment; }
	
	public void bind()
	{
		if (id == -1)
		{
			throw new IllegalStateException("Framebuffer does not exist!");
		} 
		GL43C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, id);
	}
	
	public void bindAsReadBuffer() { GL43C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, id); }
	
	public void bindAsDrawBuffer() { GL43C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, id); }
	
	public void destroyInternal()
	{
		GL43C.glDeleteFramebuffers(id); 
		this.id = -1;
	}
	
	public int getStatus()
	{
		bind(); 
		int status = GL43C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
		
		return status;
	}
	
	public int getId() { return id; }
	
}
