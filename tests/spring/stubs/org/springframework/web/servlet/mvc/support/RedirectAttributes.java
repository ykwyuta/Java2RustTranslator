package org.springframework.web.servlet.mvc.support;

/** テスト用のスタブ。 */
public interface RedirectAttributes {
    RedirectAttributes addAttribute(String name, Object value);

    RedirectAttributes addFlashAttribute(String name, Object value);
}
