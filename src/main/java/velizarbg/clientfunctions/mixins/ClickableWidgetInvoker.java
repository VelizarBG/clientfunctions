package velizarbg.clientfunctions.mixins;

import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClickableWidget.class)
public interface ClickableWidgetInvoker {
	@Invoker("setFocused")
	void invokeSetFocused(boolean focused);
}
