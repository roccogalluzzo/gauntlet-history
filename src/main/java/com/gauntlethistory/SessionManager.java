package com.gauntlethistory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class SessionManager
{
	@Inject private GauntletHistoryConfig config;
	@Inject private Gson gson;

	private final CopyOnWriteArrayList<GauntletSession> sessions = new CopyOnWriteArrayList<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private GauntletSession current;

	GauntletSession startSession(Instant startTime)
	{
		current = new GauntletSession(startTime);
		return current;
	}

	GauntletSession current()
	{
		return current;
	}

	List<GauntletSession> getSessions()
	{
		return sessions;
	}

	/**
	 * Closes the current session, persists it, and calls {@code afterSave} on the EDT when done.
	 * No-op if there is no current session.
	 */
	void finishSession(Runnable afterSave)
	{
		if (current == null)
		{
			return;
		}

		current.endTime = Instant.now();
		sessions.add(0, current);

		int max = config.maxSessions();
		if (max > 0)
		{
			while (sessions.size() > max)
			{
				sessions.remove(sessions.size() - 1);
			}
		}

		GauntletSession finished = current;
		current = null;

		executor.submit(() ->
		{
			save();
			if (config.autoExport())
			{
				try
				{
					exportHtml();
				}
				catch (IOException e)
				{
					log.warn("Failed to auto-export HTML", e);
				}
			}
			if (afterSave != null)
			{
				SwingUtilities.invokeLater(afterSave);
			}
			log.debug("Session saved: kill={} diedBoss={} kc={}",
				finished.killedBoss, finished.diedInBoss, finished.killCount);
		});
	}

	void exportHtml() throws IOException
	{
		HtmlExporter.export(new ArrayList<>(sessions), GauntletHistoryPlugin.HISTORY_DIR);
	}

	/** Loads sessions from disk asynchronously, calling {@code onLoaded} on the EDT when done. */
	void loadAsync(Runnable onLoaded)
	{
		executor.submit(() ->
		{
			load();
			if (onLoaded != null)
			{
				SwingUtilities.invokeLater(onLoaded);
			}
		});
	}

	/** Clears the current session without persisting it. */
	void discardSession()
	{
		current = null;
	}

	void shutdown()
	{
		executor.shutdownNow();
	}

	private void load()
	{
		File file = new File(GauntletHistoryPlugin.HISTORY_DIR, "sessions.json");
		if (!file.exists())
		{
			return;
		}
		try (FileReader reader = new FileReader(file))
		{
			Gson localGson = gson.newBuilder()
				.registerTypeAdapter(Instant.class, new InstantAdapter())
				.create();
			Type listType = new TypeToken<List<GauntletSession>>()
			{
			}.getType();
			List<GauntletSession> loaded = localGson.fromJson(reader, listType);
			if (loaded != null)
			{
				sessions.addAll(loaded);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to load session history", e);
		}
	}

	private void save()
	{
		File file = new File(GauntletHistoryPlugin.HISTORY_DIR, "sessions.json");
		try (FileWriter writer = new FileWriter(file))
		{
			Gson localGson = gson.newBuilder()
				.registerTypeAdapter(Instant.class, new InstantAdapter())
				.setPrettyPrinting()
				.create();
			localGson.toJson(new ArrayList<>(sessions), writer);
		}
		catch (IOException e)
		{
			log.warn("Failed to save session history", e);
		}
	}
}
