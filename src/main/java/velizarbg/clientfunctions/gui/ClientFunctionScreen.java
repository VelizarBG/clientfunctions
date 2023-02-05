package velizarbg.clientfunctions.gui;

import com.google.common.collect.Lists;
import com.google.common.math.IntMath;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextHandler;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.SelectionManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Rect2i;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import velizarbg.clientfunctions.ClientFunctionsMod;
import velizarbg.clientfunctions.ModConfig.HistoryMode;
import velizarbg.clientfunctions.mixins.ClickableWidgetInvoker;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static velizarbg.clientfunctions.Constants.CONFIG;

@Environment(EnvType.CLIENT)
public class ClientFunctionScreen extends Screen {
	public static final Identifier CFGUI_TEXTURE = new Identifier("clientfunctions", "textures/gui/cfgui.png");
	private static final int BOX_WIDTH = 336;
	private static final int BOX_HEIGHT = 189;
	private static final int BOX_OFFSET = BOX_HEIGHT / 21;
	private static final int LINE_HEIGHT = 9;
	private static final int MAX_VISIBLE_LINES = (BOX_HEIGHT - BOX_OFFSET * 2) / LINE_HEIGHT;
	private final IntSupplier relWidth = () -> (width - BOX_WIDTH) / 2;
	private final IntSupplier relHeight = () -> (height - BOX_HEIGHT) / 2;
	private final Supplier<Rect2i> textBoxRect = () -> new Rect2i(relWidth.getAsInt() + BOX_OFFSET, relHeight.getAsInt() + BOX_OFFSET, BOX_WIDTH - BOX_OFFSET * 2, BOX_HEIGHT - BOX_OFFSET * 2);
	private final Supplier<Rect2i> scrollBarRect = () -> new Rect2i(relWidth.getAsInt() + BOX_WIDTH + 1, relHeight.getAsInt() + BOX_OFFSET, 10, BOX_HEIGHT - BOX_OFFSET * 2);
	private int tickCounter;
	private final SelectionManager textBoxSelectionManager = new SelectionManager(
		ClientFunctionScreen::getTextBoxString,
		this::setTextBoxString,
		this::getClipboard,
		this::setClipboard,
		string -> string.length() < 65536
	);
	private long lastClickTime;
	private int lastClickIndex = -1;
	private ButtonWidget runButton;
	private ButtonWidget clearTextButton;
	private ButtonWidget prevButton;
	private ButtonWidget nextButton;
	private ButtonWidget clearHistoryButton;
	@Nullable private BoxContent boxContent = BoxContent.EMPTY;
	private double scrolledLines;
	private boolean updateCursor = true;
	private boolean scrolling;
	private static String textBoxString = "";
	private static String lastBoxString = "";
	public static Set<String> textBoxHistory = new LinkedHashSet<>();
	public static int historyIndex = 0;

	public ClientFunctionScreen() {
		super(ScreenTexts.EMPTY);
	}

	private void setClipboard(String clipboard) {
		if (client != null)
			SelectionManager.setClipboard(client, clipboard);
	}

	private String getClipboard() {
		return client != null ? SelectionManager.getClipboard(client) : "";
	}

	@Override
	public void tick() {
		super.tick();
		++tickCounter;
	}

	@Override
	protected void init() {
		invalidateBoxContent();
		if (client != null)
			client.keyboard.setRepeatEvents(true);

		Consumer<ButtonWidget> unfocusButton = button -> {
			((ClickableWidgetInvoker) button).invokeSetFocused(false);
			super.setFocused(null);
		};

		Function<Text, ButtonWidget.TooltipSupplier> tooltipSupplierFactory = tooltip -> new ButtonWidget.TooltipSupplier() {
			@Override
			public void onTooltip(ButtonWidget buttonWidget, MatrixStack matrixStack, int i, int j) {
				ClientFunctionScreen.this.renderTooltip(matrixStack, tooltip, i, j);
			}

			@Override
			public void supply(Consumer<Text> consumer) {
				consumer.accept(tooltip);
			}
		};

		IntConsumer scrollHistory = offset -> {
			if (CONFIG.historyMode == HistoryMode.NEVER)
				return;

			if (hasShiftDown())
				offset = offset > 0
					? textBoxHistory.size() - historyIndex
					: -historyIndex;

			int newOffset = historyIndex + offset;
			int historySize = textBoxHistory.size();
			newOffset = MathHelper.clamp(newOffset, 0, historySize);
			if (newOffset != historyIndex) {
				String toSet;
				if (newOffset == historySize) {
					historyIndex = historySize;
					toSet = lastBoxString;
				} else {
					if (historyIndex == historySize)
						lastBoxString = textBoxString;
					toSet = textBoxHistory.stream().toList().get(newOffset);
					historyIndex = newOffset;
				}
				textBoxSelectionManager.selectAll();
				textBoxSelectionManager.delete(1);
				textBoxSelectionManager.insert(toSet);
				invalidateBoxContent();
				tryMoveCursorIntoView();
			}
		};

		runButton = addDrawableChild(
			new ButtonWidget(
				relWidth.getAsInt() + BOX_WIDTH - 75 + 1, relHeight.getAsInt() + BOX_HEIGHT + 1, 75, 20, Text.literal("Run"),
				button -> {
					PacketByteBuf buf = PacketByteBufs.create();
					for (String line : textBoxString.split("\n")) {
						buf.writeString(line);
					}
					ClientPlayNetworking.send(ClientFunctionsMod.PACKET_CLIENT_FUNCTION_LINES, buf);
					if (CONFIG.historyMode != HistoryMode.NEVER) {
						textBoxHistory.remove(textBoxString);
						textBoxHistory.add(textBoxString);
						historyIndex = textBoxHistory.size();
					}
					textBoxString = "";
					client.setScreen(null);
				}
			)
		);
		clearTextButton = addDrawableChild(
			new ButtonWidget(
				relWidth.getAsInt(), relHeight.getAsInt() + BOX_HEIGHT + 1, 20, 20, Text.literal("X"),
				button -> {
					textBoxSelectionManager.selectAll();
					textBoxSelectionManager.delete(1);
					invalidateBoxContent();

					unfocusButton.accept(button);
				},
				tooltipSupplierFactory.apply(Text.translatable("clientfunctions.gui.clearTextBox"))
			)
		);
		prevButton = addDrawableChild(
			new ButtonWidget(
				relWidth.getAsInt() + BOX_WIDTH - 3 * 20 - 2 * 10, relHeight.getAsInt() - 20 - 1, 20, 20, Text.literal("<"),
				button -> {
					scrollHistory.accept(-1);

					unfocusButton.accept(button);
				}
			)
		);
		nextButton = addDrawableChild(
			new ButtonWidget(
				prevButton.x + 20 + 10, prevButton.y, 20, 20, Text.literal(">"),
				button -> {
					scrollHistory.accept(1);

					unfocusButton.accept(button);
				}
			)
		);
		clearHistoryButton = addDrawableChild(
			new ButtonWidget(
				nextButton.x + 20 + 10, nextButton.y, 20, 20, Text.literal("X"),
				button -> {
					textBoxHistory = new LinkedHashSet<>();
					historyIndex = 0;

					unfocusButton.accept(button);
				},
				tooltipSupplierFactory.apply(Text.translatable("clientfunctions.gui.clearHistory"))
			)
		);
	}

	@Override
	public void setFocused(@Nullable Element focused) {
		if (InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_TAB))
			super.setFocused(focused);
		else
			super.setFocused(null);
	}

	@Override
	public void removed() {
		if (client != null)
			client.keyboard.setRepeatEvents(false);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (super.keyPressed(keyCode, scanCode, modifiers)) {
			return true;
		} else {
			boolean success = keyPressedInternal(keyCode);
			if (success) {
				invalidateBoxContent();
				if (updateCursor) {
					tryMoveCursorIntoView();
				} else updateCursor = true;
				return true;
			} else {
				return false;
			}
		}
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (super.charTyped(chr, modifiers)) {
			return true;
		} else if (SharedConstants.isValidChar(chr)) {
			textBoxSelectionManager.insert(Character.toString(chr));
			invalidateBoxContent();
			tryMoveCursorIntoView();
			return true;
		} else {
			return false;
		}
	}

	private boolean keyPressedInternal(int keyCode) {
		if (isSelectAll(keyCode)) {
			textBoxSelectionManager.selectAll();
			return true;
		} else if (isCopy(keyCode)) {
			textBoxSelectionManager.copy();
			return true;
		} else if (isPaste(keyCode)) {
			textBoxSelectionManager.paste();
			return true;
		} else if (isCut(keyCode)) {
			textBoxSelectionManager.cut();
			return true;
		} else {
			SelectionManager.SelectionType selectionType = hasControlDown() ? SelectionManager.SelectionType.WORD : SelectionManager.SelectionType.CHARACTER;
			switch(keyCode) {
				case GLFW.GLFW_KEY_ENTER:
				case GLFW.GLFW_KEY_KP_ENTER:
					textBoxSelectionManager.insert("\n");
					return true;
				case GLFW.GLFW_KEY_BACKSPACE:
					textBoxSelectionManager.delete(-1, selectionType);
					return true;
				case GLFW.GLFW_KEY_DELETE:
					textBoxSelectionManager.delete(1, selectionType);
					return true;
				case GLFW.GLFW_KEY_RIGHT:
					textBoxSelectionManager.moveCursor(1, hasShiftDown(), selectionType);
					return true;
				case GLFW.GLFW_KEY_LEFT:
					textBoxSelectionManager.moveCursor(-1, hasShiftDown(), selectionType);
					return true;
				case GLFW.GLFW_KEY_DOWN:
					moveDownLine();
					return true;
				case GLFW.GLFW_KEY_UP:
					moveUpLine();
					return true;
				case GLFW.GLFW_KEY_PAGE_UP:
					scroll(-MAX_VISIBLE_LINES);
					updateCursor = false;
					return true;
				case GLFW.GLFW_KEY_PAGE_DOWN:
					scroll(MAX_VISIBLE_LINES);
					updateCursor = false;
					return true;
				case GLFW.GLFW_KEY_HOME:
					moveToLineStart();
					return true;
				case GLFW.GLFW_KEY_END:
					moveToLineEnd();
					return true;
				default:
					return false;
			}
		}
	}

	private void tryMoveCursorIntoView() {
		Position pos = absolutePositionToScreenPosition(getBoxContent().pos);

		if (isOutsideTextBox(pos)) {
			int textBoxY = textBoxRect.get().getY();
			scrolledLines -= (double) (textBoxY - pos.y) / LINE_HEIGHT;
			if (pos.y > textBoxY) {
				scrolledLines -= MAX_VISIBLE_LINES - 1;
			}

			invalidateBoxContent();
		}
	}

	private void moveUpLine() {
		moveVertically(-1);
	}

	private void moveDownLine() {
		moveVertically(1);
	}

	private void moveVertically(int lines) {
		int i = textBoxSelectionManager.getSelectionStart();
		int j = getBoxContent().getVerticalOffset(i, lines);
		textBoxSelectionManager.moveCursorTo(j, hasShiftDown());
	}

	private void moveToLineStart() {
		if (hasControlDown()) {
			textBoxSelectionManager.moveCursorToStart(hasShiftDown());
		} else {
			int i = textBoxSelectionManager.getSelectionStart();
			int j = getBoxContent().getLineStart(i);
			textBoxSelectionManager.moveCursorTo(j, hasShiftDown());
		}
	}

	private void moveToLineEnd() {
		if (hasControlDown()) {
			textBoxSelectionManager.moveCursorToEnd(hasShiftDown());
		} else {
			int i = textBoxSelectionManager.getSelectionStart();
			int j = getBoxContent().getLineEnd(i);
			textBoxSelectionManager.moveCursorTo(j, hasShiftDown());
		}
	}

	private static String getTextBoxString() {
		return textBoxString;
	}

	private void setTextBoxString(String newContent) {
		textBoxString = newContent;
		invalidateBoxContent();
		scroll(0);
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.setShaderTexture(0, CFGUI_TEXTURE);
		drawTexture(matrices, relWidth.getAsInt(), relHeight.getAsInt(), this.getZOffset(), 0.0F, 0.0F, BOX_WIDTH, BOX_HEIGHT, BOX_WIDTH, BOX_HEIGHT);
		BoxContent boxContent = getBoxContent();
		for (int i = 0; i < MAX_VISIBLE_LINES; i++) {
			if (i + scrolledLines < boxContent.lines.length) {
				Line line = boxContent.lines[i + (int) scrolledLines];
				textRenderer.draw(matrices, line.text, (float) line.x, (float) line.y, 237666);
			} else break;
		}

		if (boxContent.lines.length > MAX_VISIBLE_LINES)
			drawScrollBar();
		drawCursor(matrices, boxContent.pos, boxContent.atEnd);
		drawSelection(boxContent.selectionRectangles);

		super.render(matrices, mouseX, mouseY, delta);
	}

	private void drawCursor(MatrixStack matrices, Position pos, boolean atEnd) {
		if (tickCounter / 6 % 2 == 0) {
			pos = absolutePositionToScreenPosition(pos);
			if (isOutsideTextBox(pos))
				return;

			if (!atEnd) {
				DrawableHelper.fill(matrices, pos.x, pos.y - 1, pos.x + 1, pos.y + LINE_HEIGHT, 0xFFFFFFFF);
			} else {
				textRenderer.draw(matrices, "_", (float)pos.x, (float)pos.y, 0xFFFFFFFF);
			}
		}

	}

	private void drawScrollBar() {
		RenderSystem.setShaderTexture(0, DrawableHelper.OPTIONS_BACKGROUND_TEXTURE);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.disableTexture();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		Rect2i scrollBar = scrollBarRect.get();
		int barXStart = scrollBar.getX();
		int barXEnd = barXStart + scrollBar.getWidth();
		int top = scrollBar.getY();
		int bottom = top + scrollBar.getHeight();
		int lines = getBoxContent().lines.length;

		int filledBarHeight = (int) ((float) IntMath.pow(bottom - top, 2) / (lines * LINE_HEIGHT));
		filledBarHeight = MathHelper.clamp(filledBarHeight, 16, bottom - top);
		int filledBarYPos = (int) (scrolledLines * (bottom - top - filledBarHeight) / (lines - MAX_VISIBLE_LINES) + top);
		if (filledBarYPos < top) filledBarYPos = top;

		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder bufferBuilder = tessellator.getBuffer();

		bufferBuilder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		bufferBuilder.vertex(barXStart, bottom, 0.0).color(0, 0, 0, 255).next();
		bufferBuilder.vertex(barXEnd, bottom, 0.0).color(0, 0, 0, 255).next();
		bufferBuilder.vertex(barXEnd, top, 0.0).color(0, 0, 0, 255).next();
		bufferBuilder.vertex(barXStart, top, 0.0).color(0, 0, 0, 255).next();
		bufferBuilder.vertex(barXStart, (filledBarYPos + filledBarHeight), 0.0).color(128, 128, 128, 255).next();
		bufferBuilder.vertex(barXEnd, (filledBarYPos + filledBarHeight), 0.0).color(128, 128, 128, 255).next();
		bufferBuilder.vertex(barXEnd, filledBarYPos, 0.0).color(128, 128, 128, 255).next();
		bufferBuilder.vertex(barXStart, filledBarYPos, 0.0).color(128, 128, 128, 255).next();
		bufferBuilder.vertex(barXStart, (filledBarYPos + filledBarHeight - 1), 0.0).color(192, 192, 192, 255).next();
		bufferBuilder.vertex((barXEnd - 1), (filledBarYPos + filledBarHeight - 1), 0.0).color(192, 192, 192, 255).next();
		bufferBuilder.vertex((barXEnd - 1), filledBarYPos, 0.0).color(192, 192, 192, 255).next();
		bufferBuilder.vertex(barXStart, filledBarYPos, 0.0).color(192, 192, 192, 255).next();
		tessellator.draw();
	}

	private void drawSelection(Rect2i[] selectionRectangles) {
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder bufferBuilder = tessellator.getBuffer();
		RenderSystem.setShader(GameRenderer::getPositionShader);
		RenderSystem.setShaderColor(0.0F, 0.0F, 255.0F, 255.0F);
		RenderSystem.disableTexture();
		RenderSystem.enableColorLogicOp();
		RenderSystem.logicOp(GlStateManager.LogicOp.OR_REVERSE);
		bufferBuilder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);

		for(Rect2i selectionRectangle : selectionRectangles) {
			int x = selectionRectangle.getX();
			int y = selectionRectangle.getY();
			if (isOutsideTextBox(new Position(x, y)))
				continue;
			int width = x + selectionRectangle.getWidth();
			int height = y + selectionRectangle.getHeight();
			bufferBuilder.vertex(x, height, 0.0).next();
			bufferBuilder.vertex(width, height, 0.0).next();
			bufferBuilder.vertex(width, y, 0.0).next();
			bufferBuilder.vertex(x, y, 0.0).next();
		}

		tessellator.draw();
		RenderSystem.disableColorLogicOp();
		RenderSystem.enableTexture();
	}

	private Position screenPositionToAbsolutePosition(Position position) {
		return new Position(position.x - relWidth.getAsInt() - BOX_OFFSET, position.y - relHeight.getAsInt() - BOX_OFFSET);
	}

	private Position absolutePositionToScreenPosition(Position position) {
		return new Position(position.x + relWidth.getAsInt() + BOX_OFFSET, position.y + relHeight.getAsInt() + BOX_OFFSET);
	}

	private void updateScrollingState(double mouseX, double mouseY, int button) {
		scrolling = button == 0 && getBoxContent().lines.length > MAX_VISIBLE_LINES && scrollBarRect.get().contains((int) mouseX, (int) mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (hasControlDown()) {
			if (amount > 0)
				prevButton.onPress();
			else
				nextButton.onPress();

			return true;
		}

		amount = MathHelper.clamp(amount, -1.0, 1.0);
		if (!hasShiftDown()) {
			amount *= 7.0;
		}

		scroll(-(int) amount);
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		updateScrollingState(mouseX, mouseY, button);
		if (!super.mouseClicked(mouseX, mouseY, button)) {
			if (button == 0 && !scrolling) {
				long timeMs = Util.getMeasuringTimeMs();
				int cursorPosition = getBoxContent().getCursorPosition(textRenderer, screenPositionToAbsolutePosition(new Position((int) mouseX, (int) mouseY)), (int) scrolledLines);
				if (cursorPosition >= 0) {
					if (cursorPosition != lastClickIndex || timeMs - lastClickTime >= 250L) {
						textBoxSelectionManager.moveCursorTo(cursorPosition, hasShiftDown());
					} else if (!textBoxSelectionManager.isSelecting()) {
						selectCurrentWord(cursorPosition);
					} else {
						textBoxSelectionManager.selectAll();
					}

					invalidateBoxContent();
				}

				lastClickIndex = cursorPosition;
				lastClickTime = timeMs;
			}
		}
		return true;
	}

	private void selectCurrentWord(int cursor) {
		textBoxSelectionManager.setSelection(
			TextHandler.moveCursorByWords(textBoxString, -1, cursor, false),
			TextHandler.moveCursorByWords(textBoxString, 1, cursor, false)
		);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (!super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
			if (button == 0) {
				if (scrolling) {
					int barHeight = scrollBarRect.get().getHeight();
					int lines = getBoxContent().lines.length;
					double remainingLines = Math.max(1, lines - MAX_VISIBLE_LINES);
					int barToLineRatio = MathHelper.clamp((int) ((float) (barHeight * barHeight) / (float) (lines * LINE_HEIGHT)), 16, barHeight);
					double deltaMultiplier = Math.max(0.1, remainingLines / (double) (barHeight - barToLineRatio));
					scroll(deltaY * deltaMultiplier);
					return true;
				}
				textBoxSelectionManager.moveCursorTo(
					getBoxContent().getCursorPosition(textRenderer, screenPositionToAbsolutePosition(new Position((int) mouseX, (int) mouseY)), (int) scrolledLines),
					true
				);
				invalidateBoxContent();
			}
		}
		return true;
	}

	private boolean isOutsideTextBox(Position pos) {
		// '+ 1' because the objects overlap by a single pixel ¯\_(ツ)_/¯
		return !(textBoxRect.get()).contains(pos.x, pos.y + 1);
	}

	private void scroll(double amount) {
		this.scrolledLines += amount;
		int i;
		if (scrolledLines < 0 || (i = getBoxContent().lines.length) <= MAX_VISIBLE_LINES) {
			scrolledLines = 0;
		} else if (this.scrolledLines > i - MAX_VISIBLE_LINES) {
			this.scrolledLines = i - MAX_VISIBLE_LINES;
		}

		invalidateBoxContent();
	}

	private BoxContent getBoxContent() {
		if (boxContent == null)
			boxContent = createBoxContent();

		return boxContent;
	}

	private void invalidateBoxContent() {
		boxContent = null;
	}

	private BoxContent createBoxContent() {
		if (textBoxString.isEmpty()) {
			return BoxContent.EMPTY;
		} else {
			int selStart = textBoxSelectionManager.getSelectionStart();
			int selEnd = textBoxSelectionManager.getSelectionEnd();
			IntList lineStartsList = new IntArrayList();
			List<Line> lines = Lists.newArrayList();
			MutableInt mutableInt = new MutableInt(-scrolledLines);
			MutableBoolean mutableBoolean = new MutableBoolean();
			TextHandler textHandler = textRenderer.getTextHandler();
			String tempBoxString = textBoxString.endsWith("\n") ? textBoxString + " " : textBoxString;
			textHandler.wrapLines(tempBoxString, textBoxRect.get().getWidth(), Style.EMPTY, true, (style, start, end) -> {
				String substring = tempBoxString.substring(start, end);
				mutableBoolean.setValue(substring.endsWith("\n"));
				String finalString = StringUtils.stripEnd(substring, " \n");
				Position pos = absolutePositionToScreenPosition(new Position(0, mutableInt.getAndIncrement() * LINE_HEIGHT));
				lineStartsList.add(start);
				lines.add(new Line(style, finalString, pos.x, pos.y));
			});

			int[] lineStarts = lineStartsList.toIntArray();
			boolean atEnd = selStart == textBoxString.length();
			Position boxPos;
			if (atEnd && mutableBoolean.isTrue()) {
				boxPos = new Position(0, (lines.size() - (int) scrolledLines) * LINE_HEIGHT);
			} else {
				int k = getLineFromOffset(lineStarts, selStart);
				int l = textRenderer.getWidth(textBoxString.substring(lineStarts[k], selStart));
				boxPos = new Position(l, (k - (int) scrolledLines) * LINE_HEIGHT);
			}

			List<Rect2i> selectionRectangles = Lists.newArrayList();
			if (selStart != selEnd) {
				int selMin = Math.min(selStart, selEnd);
				int selMax = Math.max(selStart, selEnd);
				int selMinOffset = getLineFromOffset(lineStarts, selMin);
				int selMaxOffset = getLineFromOffset(lineStarts, selMax);
				if (selMinOffset == selMaxOffset) {
					selectionRectangles.add(
						getLineSelectionRectangle(
							textBoxString,
							textHandler,
							selMin,
							selMax,
							(selMinOffset - (int) scrolledLines) * LINE_HEIGHT,
							lineStarts[selMinOffset]
						)
					);
				} else {
					selectionRectangles.add(
						getLineSelectionRectangle(
							textBoxString,
							textHandler,
							selMin,
							selMinOffset + 1 > lineStarts.length ? textBoxString.length() : lineStarts[selMinOffset + 1],
							(selMinOffset - (int) scrolledLines) * LINE_HEIGHT,
							lineStarts[selMinOffset]
						)
					);

					for(int i = selMinOffset + 1; i < selMaxOffset; ++i) {
						int height = (i - (int) scrolledLines) * LINE_HEIGHT;
						selectionRectangles.add(
							getRectFromCorners(
								new Position(0, height),
								new Position(
									(int) textHandler.getWidth(textBoxString.substring(lineStarts[i], lineStarts[i + 1])),
									height + LINE_HEIGHT
								)
							)
						);
					}

					selectionRectangles.add(
						getLineSelectionRectangle(
							textBoxString,
							textHandler,
							lineStarts[selMaxOffset],
							selMax,
							(selMaxOffset - (int) scrolledLines) * LINE_HEIGHT,
							lineStarts[selMaxOffset]
						)
					);
				}
			}

			return new BoxContent(
				boxPos, atEnd, lineStarts, lines.toArray(new Line[0]), selectionRectangles.toArray(new Rect2i[0])
			);
		}
	}

	static int getLineFromOffset(int[] lineStarts, int position) {
		int line = Arrays.binarySearch(lineStarts, position);
		return line < 0 ? -(line + 2) : line;
	}

	private Rect2i getLineSelectionRectangle(String string, TextHandler handler, int selectionStart, int selectionEnd, int lineY, int lineStart) {
		String selStartString = string.substring(lineStart, selectionStart);
		String selEndString = string.substring(lineStart, selectionEnd);
		Position selStartPos = new Position((int)handler.getWidth(selStartString), lineY);
		Position selEndPos = new Position((int)handler.getWidth(selEndString), lineY + LINE_HEIGHT);
		return getRectFromCorners(selStartPos, selEndPos);
	}

	private Rect2i getRectFromCorners(Position start, Position end) {
		Position adjStart = absolutePositionToScreenPosition(start);
		Position adjEnd = absolutePositionToScreenPosition(end);
		int minX = Math.min(adjStart.x, adjEnd.x);
		int maxX = Math.max(adjStart.x, adjEnd.x);
		int minY = Math.min(adjStart.y, adjEnd.y);
		int maxY = Math.max(adjStart.y, adjEnd.y);
		return new Rect2i(minX, minY, maxX - minX, maxY - minY);
	}

	static class BoxContent {
		static final BoxContent EMPTY = new BoxContent(
			new Position(0, 0), true, new int[]{0}, new Line[]{new Line(Style.EMPTY, "", 0, 0)}, new Rect2i[0]
		);
		final Position pos;
		final boolean atEnd;
		private final int[] lineStarts;
		final Line[] lines;
		final Rect2i[] selectionRectangles;

		public BoxContent(
			Position pos, boolean atEnd, int[] lineStarts, Line[] lines, Rect2i[] selectionRectangles
		) {
			this.pos = pos;
			this.atEnd = atEnd;
			this.lineStarts = lineStarts;
			this.lines = lines;
			this.selectionRectangles = selectionRectangles;
		}

		public int getCursorPosition(TextRenderer renderer, Position position, int scrolledLines) {
			int lineIndex = (position.y / LINE_HEIGHT) + scrolledLines;
			{
				int diff = lineIndex - scrolledLines;
				if (diff >= MAX_VISIBLE_LINES) {
					lineIndex = scrolledLines + MAX_VISIBLE_LINES - 1;
				} else if (diff <= 0) {
					lineIndex = scrolledLines;
				}
			}
			lineIndex = MathHelper.clamp(lineIndex, 0, lines.length - 1);
			Line line = lines[lineIndex];
			return lineStarts[lineIndex] + renderer.getTextHandler().getTrimmedLength(line.content, position.x, line.style);
		}

		public int getVerticalOffset(int position, int lines) {
			int lineFromOffset = ClientFunctionScreen.getLineFromOffset(lineStarts, position);
			int lineIndex = lineFromOffset + lines;
			int offset;
			if (0 <= lineIndex && lineIndex < lineStarts.length) {
				int k = position - lineStarts[lineFromOffset];
				int l = this.lines[lineIndex].content.length();
				offset = lineStarts[lineIndex] + Math.min(k, l);
			} else {
				offset = position;
			}

			return offset;
		}

		public int getLineStart(int position) {
			int lineFromOffset = ClientFunctionScreen.getLineFromOffset(lineStarts, position);
			return lineStarts[lineFromOffset];
		}

		public int getLineEnd(int position) {
			int lineFromOffset = ClientFunctionScreen.getLineFromOffset(lineStarts, position);
			return lineStarts[lineFromOffset] + lines[lineFromOffset].content.length();
		}
	}

	static class Line {
		final Style style;
		final String content;
		final Text text;
		final int x;
		final int y;

		public Line(Style style, String content, int x, int y) {
			this.style = style;
			this.content = content;
			this.x = x;
			this.y = y;
			text = Text.literal(content).setStyle(style);
		}
	}

	static class Position {
		public final int x;
		public final int y;

		Position(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}
}
