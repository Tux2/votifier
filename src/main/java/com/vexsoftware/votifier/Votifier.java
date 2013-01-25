/*
 * Copyright (C) 2012 Vex Software LLC
 * This file is part of Votifier.
 * 
 * Votifier is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Votifier is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Votifier.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vexsoftware.votifier;

import java.io.*;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.*;

import net.minecraft.server.MinecraftServer;

import com.vexsoftware.votifier.crypto.RSAIO;
import com.vexsoftware.votifier.crypto.RSAKeygen;
import com.vexsoftware.votifier.model.ListenerLoader;
import com.vexsoftware.votifier.model.VoteListener;
import com.vexsoftware.votifier.net.VoteReceiver;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.Mod.PreInit;
import cpw.mods.fml.common.Mod.ServerStarted;
import cpw.mods.fml.common.Mod.ServerStopping;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * The main Votifier plugin class.
 * 
 * @author Blake Beaupain
 * @author Kramer Campbell
 */
@Mod(modid="Votifier", name="Votifier", version="1.9.0")
public class Votifier {

	private VotifierConfig cfg;
	
	/** The logger instance. */
	private static final Logger LOG = MinecraftServer.logger;

	/** Log entry prefix */
	private static final String logPrefix = "[Votifier] ";

	/** The Votifier instance. */
	private static Votifier instance;

	/** The current Votifier version. */
	private String version = "1.9.0";

	/** The vote listeners. */
	private final List<VoteListener> listeners = new ArrayList<VoteListener>();

	/** The vote receiver. */
	private VoteReceiver voteReceiver;

	/** The RSA key pair. */
	private KeyPair keyPair;

	/** Debug mode flag */
	private boolean debug;
	
	private File datafolder = new File("config" + File.separator + "Votifier");
	
	@PreInit
	public void preInit(FMLPreInitializationEvent event) {
		LOG.info("[Votifier] Loading Votifier");
		Votifier.instance = this;

		// Handle configuration.
		if (!getDataFolder().exists()) {
			getDataFolder().mkdir();
		}
		File config = new File(getDataFolder().getAbsolutePath());
		cfg = new VotifierConfig(config);
		File rsaDirectory = new File(getDataFolder() + File.separator + "rsa");
		String listenerDirectory = getDataFolder().toString() + File.separator + "listeners";
		/*
		 * Use IP address from server.properties as a default for
		 * configurations. Do not use InetAddress.getLocalHost() as it most
		 * likely will return the main server address instead of the address
		 * assigned to the server.
		 */
		String hostAddr = MinecraftServer.getServer().getHostname();
		if (hostAddr == null || hostAddr.length() == 0)
			hostAddr = "0.0.0.0";

		/*
		 * Create configuration file if it does not exists; otherwise, load it
		 */
		if (cfg.getString("port","").equals("")) {
			try {
				// First time run - do some initialization.
				LOG.info("Configuring Votifier for the first time...");

				// Initialize the configuration file.
				cfg.set("host", hostAddr);
				cfg.set("port", 8192);
				cfg.set("debug", false);

				/*
				 * Remind hosted server admins to be sure they have the right
				 * port number.
				 */
				LOG.info("------------------------------------------------------------------------------");
				LOG.info("Assigning Votifier to listen on port 8192. If you are hosting Craftbukkit on a");
				LOG.info("shared server please check with your hosting provider to verify that this port");
				LOG.info("is available for your use. Chances are that your hosting provider will assign");
				LOG.info("a different port, which you need to specify in config.yml");
				LOG.info("------------------------------------------------------------------------------");

				cfg.set("listener_folder", listenerDirectory);
				cfg.save();
			} catch (Exception ex) {
				LOG.log(Level.SEVERE, "Error creating configuration file", ex);
				gracefulExit();
				return;
			}
		}

		/*
		 * Create RSA directory and keys if it does not exist; otherwise, read
		 * keys.
		 */
		try {
			if (!rsaDirectory.exists()) {
				rsaDirectory.mkdir();
				new File(listenerDirectory).mkdir();
				keyPair = RSAKeygen.generate(2048);
				RSAIO.save(rsaDirectory, keyPair);
			} else {
				keyPair = RSAIO.load(rsaDirectory);
			}
		} catch (Exception ex) {
			LOG.log(Level.SEVERE,
					"Error reading configuration file or RSA keys", ex);
			gracefulExit();
			return;
		}
	}

	@Init
	public void onEnable(FMLInitializationEvent event) {

		LOG.info("[Votifier] Loading vote listeners");
		String listenerDirectory = getDataFolder().toString() + File.separator + "listeners";

		// Load the vote listeners.
		listenerDirectory = cfg.getString("listener_folder");
		listeners.addAll(ListenerLoader.load(listenerDirectory));

	}
	
	@ServerStarted
	public void serverStarted(FMLServerStartedEvent event) {
		// Initialize the receiver.
		String host = cfg.getString("host");
		int port = cfg.getInt("port", 8192);
		LOG.info("[Votifier] Starting listener on port " + port);
		debug = cfg.getBoolean("debug", false);
		if (debug) {
			LOG.info("DEBUG mode enabled!");
		}
		
		try {
			voteReceiver = new VoteReceiver(this, host, port);
			voteReceiver.start();

			LOG.info("Votifier enabled.");
		} catch (Exception ex) {
			gracefulExit();
			return;
		}
		
	}

	@ServerStopping
	public void serverStopped(FMLServerStoppingEvent event) {
		// Interrupt the vote receiver.
		if (voteReceiver != null) {
			voteReceiver.shutdown();
		}
		LOG.info("Votifier disabled.");
	}

	private void gracefulExit() {
		LOG.log(Level.SEVERE, "Votifier did not initialize properly!");
	}

	/**
	 * Gets the instance.
	 * 
	 * @return The instance
	 */
	public static Votifier getInstance() {
		return instance;
	}

	/**
	 * Gets the version.
	 * 
	 * @return The version
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Gets the listeners.
	 * 
	 * @return The listeners
	 */
	public List<VoteListener> getListeners() {
		return listeners;
	}

	/**
	 * Gets the vote receiver.
	 * 
	 * @return The vote receiver
	 */
	public VoteReceiver getVoteReceiver() {
		return voteReceiver;
	}

	/**
	 * Gets the keyPair.
	 * 
	 * @return The keyPair
	 */
	public KeyPair getKeyPair() {
		return keyPair;
	}

	public boolean isDebug() {
		return debug;
	}

	public File getDataFolder() {
		return datafolder;
	}

}
