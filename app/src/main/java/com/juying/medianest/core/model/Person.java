package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演职人员：官方 person/list/{itemGuid} 的人员条目。
 *
 * <p>字段对应网页端：person_guid / name / original_name / role / job / profile_path /
 * biography。role 是该人在某部作品里的角色名（如「Kaguya (voice)」），
 * job 是职务（Actor / Director / Writer），详情页按 job 分节展示作品。
 */
public final class Person {

    public String personGuid = "";
    public String name = "";
    public String originalName = "";
    /** 在作品中的角色名，可能为空（导演/编剧）。 */
    public String role = "";
    /** 职务：Actor / Director / Writer 等，NAS 原值。 */
    public String job = "";
    public String profilePath = "";
    public String biography = "";
    public int order = 0;

    /** 官方分节标题：作为演员 / 作为导演 / 作为编剧；其它职务原样显示。 */
    public String sectionTitle() {
        if ("Actor".equalsIgnoreCase(job)) {
            return "作为演员";
        }
        if ("Director".equalsIgnoreCase(job)) {
            return "作为导演";
        }
        if ("Writer".equalsIgnoreCase(job) || "Screenplay".equalsIgnoreCase(job)) {
            return "作为编剧";
        }
        return job == null || job.length() == 0 ? "作品" : job;
    }

    public static Person fromJson(Map<String, Object> m) {
        Person p = new Person();
        p.personGuid = Json.str(m, "person_guid", "");
        p.name = Json.str(m, "name", "");
        p.originalName = Json.str(m, "original_name", "");
        p.role = Json.str(m, "role", "");
        p.job = Json.str(m, "job", "");
        p.profilePath = Json.str(m, "profile_path", "");
        p.biography = Json.str(m, "biography", "");
        p.order = Json.integer(m, "order", 0);
        return p;
    }

    /** GET person/{guid} 的 data 结构（字段名与列表略有差异：profile / imdbId）。 */
    public static Person fromDetailJson(Map<String, Object> m) {
        Person p = new Person();
        p.personGuid = Json.str(m, "guid", "");
        p.name = Json.str(m, "name", "");
        p.originalName = Json.str(m, "original_name", "");
        p.profilePath = Json.str(m, "profile", "");
        p.biography = Json.str(m, "biography", "");
        return p;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("person_guid", personGuid);
        m.put("name", name);
        m.put("original_name", originalName);
        m.put("role", role);
        m.put("job", job);
        m.put("profile_path", profilePath);
        m.put("biography", biography);
        m.put("order", Integer.valueOf(order));
        return m;
    }
}
