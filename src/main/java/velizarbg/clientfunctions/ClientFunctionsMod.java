package velizarbg.clientfunctions;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ClientFunctionsMod implements ModInitializer {
	public static final Identifier PACKET_CLIENT_FUNCTION_LINES = new Identifier("clientfunctions", "clientfunctionlines");
	public static final ArrayDeque<Pair<ServerPlayerEntity, CommandFunction>> CLIENT_FUNCTIONS_QUEUE = new ArrayDeque<>();
	private static final Random RANDOM = new Random();

	@Override
	public void onInitialize() {
		ServerPlayNetworking.registerGlobalReceiver(ClientFunctionsMod.PACKET_CLIENT_FUNCTION_LINES, (server, player, handler, buf, response) -> {
			if (!player.hasPermissionLevel(2)) {
				player.sendMessage(Text.literal("You do not have sufficient permissions to perform this action").formatted(Formatting.RED));
				return;
			}

			List<String> list = new ArrayList<>();
			try {
				while (buf.isReadable()) {
					list.add(buf.readString());
				}
			} catch (Throwable t) {
				player.sendMessage(Text.literal("Something went wrong while reading the packet: " + t.getMessage()).formatted(Formatting.RED));
			}
			try {
				CLIENT_FUNCTIONS_QUEUE.add(
					new Pair<>(
						player,
						CommandFunction.create(
							Identifier.of(
								player.getEntityName(),
								String.valueOf(RANDOM.nextInt())
							),
							server.getCommandManager().getDispatcher(),
							player.getCommandSource(),
							list
						)
					)
				);
			} catch (RuntimeException ex) {
				player.sendMessage(Text.literal(ex.getMessage()).formatted(Formatting.RED));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Pair<ServerPlayerEntity, CommandFunction> pair : CLIENT_FUNCTIONS_QUEUE) {
				ServerCommandSource source = pair.getLeft().getCommandSource();
				source.getServer().getCommandFunctionManager().execute(pair.getRight(), source.withSilent().withMaxLevel(2));
				CLIENT_FUNCTIONS_QUEUE.removeFirst();
			}
		});
	}
}
