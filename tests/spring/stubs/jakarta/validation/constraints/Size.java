package jakarta.validation.constraints;

/** テスト用のスタブ。 */
public @interface Size {
    int min() default 0;

    int max() default Integer.MAX_VALUE;

    String message() default "";
}
