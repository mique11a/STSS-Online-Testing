package com.stss.online_testing.dto;

import java.util.Date;
import lombok.Data;

/**
 * 学生考试列表项：合并 exam_paper 与 student_exam_record。
 */
@Data
public class StudentExamRecordVO {

    // ── 考试基本信息（来自 exam_paper） ──
    private Long examId;
    private String examTitle;
    private Integer totalScore;
    private Integer durationMins;
    private Date validStartTime;
    private Date validEndTime;
    private Integer paperStatus; // 1=已发布, 2=已撤回

    // ── 学生考试记录（来自 student_exam_record，可能为 null） ──
    private Long recordId;
    private Integer recordStatus; // 0=考试中, 1=已完成, 2=已作废
    private Integer studentScore; // 学生得分
    private Date submitTime;
}
