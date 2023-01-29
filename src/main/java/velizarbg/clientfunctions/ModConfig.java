package velizarbg.clientfunctions;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = "clientfunctions")
@Config.Gui.Background("minecraft:textures/block/dried_kelp_top.png")
public class ModConfig implements ConfigData {
	public static ModConfig init() {
		AutoConfig.register(ModConfig.class, JanksonConfigSerializer::new);
		return AutoConfig.getConfigHolder(ModConfig.class).getConfig();
	}

	@ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
	@Comment("""
		How should the history of executed functions be saved?
		NEVER: Never saved
		REJOIN: Saved through rejoins
		RESTART: Saved through game startups""")
	public HistoryMode historyMode = HistoryMode.REJOIN;

	public enum HistoryMode {
		NEVER, REJOIN, RESTART;

		@Override
		public String toString() {
			return "clientfunctions.history." + this.name().toLowerCase();
		}
	}
}
