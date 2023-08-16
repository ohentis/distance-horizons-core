package com.seibel.distanthorizons.core.config.gui;

import javax.swing.*;
import java.awt.*;

public class ConfigScreen extends JFrame
{
	
	public ConfigScreen()
	{
		setLayout(new GridBagLayout());
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 0.5;
		constraints.gridx = 0;
		constraints.gridy = 0;
		add(new JLabel("Hello World!"), constraints);
	}
	
	
	public static void main(String[] args)
	{
		SwingUtilities.invokeLater(() -> {
			JFrame frame = new ConfigScreen();
			frame.setSize(300, 200);
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setVisible(true);
		});
	}
	
}
