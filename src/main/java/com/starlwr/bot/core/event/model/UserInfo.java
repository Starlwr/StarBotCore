package com.starlwr.bot.core.event.model;

import lombok.*;

/**
 * 用户信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class UserInfo {
    /**
     * UID
     */
    private String uid;

    /**
     * 昵称
     */
    private String uname;

    /**
     * 头像
     */
    private String face;

    public UserInfo(String uid, String uname) {
        this.uid = uid;
        this.uname = uname;
    }

    public UserInfo(String uid, String uname, String face) {
        this.uid = uid;
        this.uname = uname;
        this.face = face;
    }
}
