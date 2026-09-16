package com.mailmaster.android;

import java.io.Serializable;

/** 邮件附件信息（附件内容已保存到本地文件，这里只记录元信息与路径）。 */
public class Attachment implements Serializable {

    private static final long serialVersionUID = 1L;

    public String name;    // 文件名（已解码，中文名正常显示）
    public String path;    // 本地保存路径
    public String mime;    // 原始 Content-Type
    public long size;      // 字节大小

    /** 友好的文件大小显示 */
    public String sizeText() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }
}
