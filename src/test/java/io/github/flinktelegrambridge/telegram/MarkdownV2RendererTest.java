package io.github.flinktelegrambridge.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownV2RendererTest {
    @Test void rendersHeadingsAndLists() { assertEquals("*Title*\n• item", MarkdownV2Renderer.render("# Title\n- item")); }
    @Test void rendersBoldBeforeItalic() { assertEquals("*bold* _italic_", MarkdownV2Renderer.render("**bold** *italic*")); }
    @Test void preservesInlineAndFencedCode() { assertEquals("`a_b`\n```x_y```", MarkdownV2Renderer.render("`a_b`\n```x_y```")); }
    @Test void rendersAndEscapesLinks() { assertEquals("[a\\!b](https://x.example/a)", MarkdownV2Renderer.render("[a!b](https://x.example/a)")); }
    @Test void escapesAllPlainMarkdownV2Specials() { assertEquals("\\_\\*\\[\\]\\(\\)\\~\\`\\>\\#\\+\\-\\=\\|\\{\\}\\.\\!", MarkdownV2Renderer.render("_*[]()~`>#+-=|{}.!")); }
    @Test void degradesUnclosedConstructsWithoutThrowing() { assertDoesNotThrow(() -> MarkdownV2Renderer.render("**unclosed [link](")); }
}
