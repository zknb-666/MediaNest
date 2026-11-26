package com.juying.medianest.core.model;

import java.util.ArrayList;
import java.util.List;

/** 媒体库（/v/api/v1/mdb/list）：动态反映网页端「媒体库」分组下的所有库。 */
public final class Library {
    public String guid = "";
    public String name = "";
    /** Mix / Movie / TV / Others / IPTV，决定导航图标。 */
    public String category = "";
    /** 该库封面海报（最多 4 张拼图），相对路径。 */
    public List<String> posters = new ArrayList<String>();
    /** 片库内条目数，由 UI 按 ancestor_guid 统计回填。 */
    public int count = 0;
}
