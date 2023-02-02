package velizarbg.clientfunctions.mixins;

import net.minecraft.client.util.SelectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SelectionManager.class)
public interface SelectionManagerInvoker {
	@Invoker("moveCursorToStart")
	void invokeMoveCursorToStart(boolean shiftDown);

	@Invoker("moveCursorToEnd")
	void invokeMoveCursorToEnd(boolean shiftDown);
}
