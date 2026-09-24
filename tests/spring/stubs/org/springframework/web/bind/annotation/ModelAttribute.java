package org.springframework.web.bind.annotation;

/** テスト用のスタブ。 */
public @interface ModelAttribute {
    String value() default "";

    String name() default "";
}
