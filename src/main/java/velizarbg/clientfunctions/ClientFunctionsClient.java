package velizarbg.clientfunctions;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.apache.commons.io.FileUtils;
import org.lwjgl.glfw.GLFW;
import velizarbg.clientfunctions.gui.ClientFunctionScreen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.List;

public class ClientFunctionsClient implements ClientModInitializer {
	private final KeyBinding openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.clientfunctions.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.clientfunctions"));

	@Override
	public void onInitializeClient() {
		Constants.CONFIG = ModConfig.init();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openGui.wasPressed() && !(client.currentScreen instanceof ClientFunctionScreen)) {
				client.setScreen(new ClientFunctionScreen());
			}
		});

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (Constants.CONFIG.historyMode != ModConfig.HistoryMode.RESTART)
				return;

			File dir = client.runDirectory.toPath().resolve("config/clientfunctions").toFile();
			if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
				List<File> files = (List<File>) FileUtils.listFiles(dir, new String[]{"mcfunction"}, false);
				files.sort(Comparator.comparing(file -> file.getName().length())); // listFiles() only sorts them alphabetically, so we tweak it a bit (refer to https://stackoverflow.com/a/50637385)
				for(File functionFile : files) {
					try {
						ClientFunctionScreen.textBoxHistory.add(FileUtils.readFileToString(functionFile, Charset.defaultCharset()));
					} catch (IOException ioException) {
						throw new RuntimeException(ioException);
					}
				}
				ClientFunctionScreen.historyIndex = ClientFunctionScreen.textBoxHistory.size();
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (Constants.CONFIG.historyMode != ModConfig.HistoryMode.RESTART)
				return;

			File dir = client.runDirectory.toPath().resolve("config/clientfunctions").toFile();
			if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
				try {
					FileUtils.cleanDirectory(dir);
				} catch (IOException ioException) {
					throw new RuntimeException(ioException);
				}
				int i = 0;
				for (String functionString : ClientFunctionScreen.textBoxHistory) {
					File functionFile = new File(dir, i + ".mcfunction");
					try {
						FileUtils.writeStringToFile(functionFile, functionString, Charset.defaultCharset());
					} catch (IOException ioException) {
						throw new RuntimeException(ioException);
					}
					i++;
				}
			}
		});
	}
}
