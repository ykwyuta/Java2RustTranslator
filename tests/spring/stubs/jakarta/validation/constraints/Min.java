package jakarta.validation.constraints;

/** テスト用のスタブ。 */
public @interface Min {
    long value();

    String message() default "";
}
