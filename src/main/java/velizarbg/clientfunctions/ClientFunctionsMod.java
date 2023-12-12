package velizarbg.clientfunctions;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.ReturnValueConsumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.FunctionCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.CommandFunctionManager;
import net.minecraft.server.function.MacroException;
import net.minecraft.server.function.Procedure;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.profiler.Profiler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientFunctionsMod implements ModInitializer {
	public static final Identifier PACKET_CLIENT_FUNCTION_LINES = new Identifier("clientfunctions", "clientfunctionlines");
	public static final Logger LOGGER = LoggerFactory.getLogger("clientfunctions");
	private static final ArrayDeque<Pair<ServerPlayerEntity, FunAndArgs>> CLIENT_FUNCTIONS_QUEUE = new ArrayDeque<>();
	private static final Random RANDOM = new Random();
	private static final Pattern ARGS_PATTERN = Pattern.compile("#\\s*args=(?<args>\\{.*?\\})");

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
				// TODO: Handle macro completion from blocks/entities/storage. Could be most convenient to create a custom command to call client functions with while also receiving args, and will bring the opportunity to create a registry of client functions
				CommandFunction<ServerCommandSource> fun = CommandFunction.create(
					Identifier.of(
						player.getNameForScoreboard().toLowerCase(),
						String.valueOf(RANDOM.nextInt())
					),
					server.getCommandManager().getDispatcher(),
					player.getCommandSource(),
					list
				);
				Matcher matcher = ARGS_PATTERN.matcher(list.get(0));
				NbtCompound args = matcher.find() ? StringNbtReader.parse(matcher.group("args")) : null;
				CLIENT_FUNCTIONS_QUEUE.add(new Pair<>(player, new FunAndArgs(fun, args)));
			} catch (RuntimeException | CommandSyntaxException ex) {
				player.sendMessage(Text.literal(ex.getMessage()).formatted(Formatting.RED));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Pair<ServerPlayerEntity, FunAndArgs> pair : CLIENT_FUNCTIONS_QUEUE) {
				ServerCommandSource source = pair.getLeft().getCommandSource();
				FunAndArgs funAndArgs = pair.getRight();
				execute(funAndArgs.fun, funAndArgs.args, FunctionCommand.createFunctionCommandSource(source));
				CLIENT_FUNCTIONS_QUEUE.removeFirst();
			}
		});
	}

	/**
	 * A clone of {@link CommandFunctionManager#execute(CommandFunction, ServerCommandSource)} but with definable macro args
	 */
	private static void execute(CommandFunction<ServerCommandSource> function, @Nullable NbtCompound args, ServerCommandSource source) {
		Profiler profiler = source.getServer().getProfiler();
		profiler.push(() -> "function " + function.id());

		try {
			Procedure<ServerCommandSource> procedure = function.withMacroReplaced(args, source.getServer().getCommandFunctionManager().getDispatcher(), source);
			CommandManager.callWithContext(source, context -> CommandExecutionContext.enqueueProcedureCall(context, procedure, source, ReturnValueConsumer.EMPTY));
		} catch (MacroException ignored) {
		} catch (Exception ex) {
			LOGGER.warn("Failed to execute function {}", function.id(), ex);
		} finally {
			profiler.pop();
		}
	}

	private record FunAndArgs(CommandFunction<ServerCommandSource> fun, @Nullable NbtCompound args) {}
}
