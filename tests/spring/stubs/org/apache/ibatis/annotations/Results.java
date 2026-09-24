package org.apache.ibatis.annotations;

/** テスト用のスタブ。 */
public @interface Results {
    String id() default "";

    Result[] value() default {};
}
