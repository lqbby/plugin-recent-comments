package com.lqbby.ethereal.comments;

/** 评论所属主体的引用视图，等价于 {@code spec.subjectRef}。 */
public record SubjectRefView(String group, String version, String kind, String name) {
}
