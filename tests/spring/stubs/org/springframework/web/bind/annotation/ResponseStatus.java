package org.springframework.web.bind.annotation;

import org.springframework.http.HttpStatus;

/** テスト用のスタブ。 */
public @interface ResponseStatus {
    HttpStatus value() default HttpStatus.INTERNAL_SERVER_ERROR;
}
