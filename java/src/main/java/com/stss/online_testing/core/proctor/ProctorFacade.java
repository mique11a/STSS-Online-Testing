package com.stss.online_testing.core.proctor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.core.storage.StorageCommand;
import com.stss.online_testing.dto.ExamPaperStudentVO;
import com.stss.online_testing.entity.ExamPaper;
import com.stss.online_testing.entity.ExamPaperQuestion;
import com.stss.online_testing.entity.ExamRuntimeConfig;
import com.stss.online_testing.entity.Question;
import com.stss.online_testing.entity.StudentExamAnswer;
import com.stss.online_testing.entity.StudentExamRecord;
import com.stss.online_testing.mapper.ExamPaperMapper;
import com.stss.online_testing.mapper.ExamPaperQuestionMapper;
import com.stss.online_testing.mapper.ExamRuntimeConfigMapper;
import com.stss.online_testing.mapper.QuestionMapper;
import com.stss.online_testing.mapper.StudentExamAnswerMapper;
import com.stss.online_testing.mapper.StudentExamRecordMapper;
import com.stss.online_testing.core.redis.ExamPaperRedisPublisher;
import com.stss.online_testing.core.redis.ProctorControllerClient;
import com.stss.online_testing.service.IExamPaperService;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProctorFacade {

    private final IExamPaperService examPaperService;
    private final ExamPaperMapper examPaperMapper;
    private final ExamPaperQuestionMapper examPaperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final StudentExamRecordMapper studentExamRecordMapper;
    private final StudentExamAnswerMapper studentExamAnswerMapper;
    private final ExamRuntimeConfigMapper examRuntimeConfigMapper;
    private final ObjectMapper objectMapper;
    private final ExamPaperRedisPublisher examPaperRedisPublisher;
    private final ProctorControllerClient proctorControllerClient;
    private final StringRedisTemplate redisTemplate;

    public ProctorFacade(
            IExamPaperService examPaperService,
            ExamPaperMapper examPaperMapper,
            ExamPaperQuestionMapper examPaperQuestionMapper,
            QuestionMapper questionMapper,
            StudentExamRecordMapper studentExamRecordMapper,
            StudentExamAnswerMapper studentExamAnswerMapper,
            ExamRuntimeConfigMapper examRuntimeConfigMapper,
            ObjectMapper objectMapper,
            ObjectProvider<ExamPaperRedisPublisher> examPaperRedisPublisherProvider,
            ObjectProvider<ProctorControllerClient> proctorControllerClientProvider,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.examPaperService = examPaperService;
        this.examPaperMapper = examPaperMapper;
        this.examPaperQuestionMapper = examPaperQuestionMapper;
        this.questionMapper = questionMapper;
        this.studentExamRecordMapper = studentExamRecordMapper;
        this.studentExamAnswerMapper = studentExamAnswerMapper;
        this.examRuntimeConfigMapper = examRuntimeConfigMapper;
        this.objectMapper = objectMapper;
        this.examPaperRedisPublisher = examPaperRedisPublisherProvider.getIfAvailable();
        this.proctorControllerClient = proctorControllerClientProvider.getIfAvailable();
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public Object execute(StorageCommand command) {
        return switch (command.getAction()) {
            case "begin_an_exam" -> beginExam(command);
            case "save_exam_progress" -> saveExamProgress(command);
            case "submit_exam_answers" -> submitExam(command);
            case "get_exam_record_review" -> getExamRecordReview(command);
            case "list_my_exam_records" -> listMyExamRecords(command);
            default -> throw new IllegalArgumentException("Proctor 不支持的 action: " + command.getAction());
        };
    }

    private Map<String, Object> beginExam(StorageCommand command) {
        requireStudent(command);

        Long examId = getLong(command.getPayload(), "id");
        Long studentId = requireOperatorId(command);
        ExamPaperStudentVO paper = examPaperService.getExamPaperForStudent(examId);
        ExamPaper examPaper = loadExamPaperOrThrow(examId);
        ExamRuntimeConfig config = getRuntimeConfig(examId);
        int submittedCount = countSubmittedAttempts(examId, studentId);

        StudentExamRecord draft = findDraftRecord(examId, studentId);
        if (draft == null && submittedCount >= config.getAllowedAttempts()) {
            throw ApiBusinessException.conflict("已达到允许考试次数上限");
        }
        if (draft == null) {
            draft = new StudentExamRecord();
            draft.setExamId(examId);
            draft.setStudentId(studentId);
            draft.setCourseId(examPaper.getCourseId());
            draft.setStatus(0);
            draft.setTotalScore(0);
            studentExamRecordMapper.insert(draft);
        }

        publishExamPaper(examId);
        ProctorControllerClient.ProctorStartResult proctorStartResult = startProctorIfEnabled(examId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", draft.getId());
        data.put("attemptsUsed", submittedCount);
        data.put("remainingAttempts", Math.max(config.getAllowedAttempts() - submittedCount, 0));
        data.put("paper", paper);
        data.put("savedAnswers", loadAnswerMap(draft.getId()));
        if (proctorStartResult != null && proctorStartResult.wsEndpoint() != null) {
            data.put("wsEndpoint", proctorStartResult.wsEndpoint());
        }
        return data;
    }

    private Map<String, Object> saveExamProgress(StorageCommand command) {
        requireStudent(command);

        Long examId = getLong(command.getPayload(), "examId");
        Long studentId = requireOperatorId(command);
        StudentExamRecord record = resolveDraftRecord(examId, studentId, command.getPayload(), false);
        List<AnswerPayload> answers = readAnswers(command.getPayload());
        validateAnswers(answers, loadPaperQuestionIds(examId));
        syncAnswers(record.getId(), answers);

        return Map.of("recordId", record.getId(), "saved", true);
    }

    private Map<String, Object> submitExam(StorageCommand command) {
        requireStudent(command);

        Long examId = getLong(command.getPayload(), "examId");
        Long studentId = requireOperatorId(command);
        StudentExamRecord record = resolveDraftRecord(examId, studentId, command.getPayload(), false);

        List<AnswerPayload> answers = readAnswers(command.getPayload());
        List<ExamPaperQuestion> relations = loadPaperRelationsOrThrow(examId);
        Set<Long> allowedQuestionIds =
                relations.stream().map(ExamPaperQuestion::getQuestionId).collect(Collectors.toSet());
        validateAnswers(answers, allowedQuestionIds);
        syncAnswers(record.getId(), answers);

        Map<Long, StudentExamAnswer> answerMap = loadAnswerDetails(record.getId());
        List<Long> paperQuestionIds = relations.stream().map(ExamPaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(paperQuestionIds)
                .stream()
                .collect(Collectors.toMap(Question::getId, question -> question));
        if (questionMap.size() != paperQuestionIds.size()) {
            List<Long> missingQuestionIds = new ArrayList<>();
            for (Long questionId : paperQuestionIds) {
                if (!questionMap.containsKey(questionId)) {
                    missingQuestionIds.add(questionId);
                }
            }
            throw ApiBusinessException.unprocessable(
                    "试卷包含已删除或不存在的题目，无法交卷: " + missingQuestionIds);
        }

        int totalScore = 0;
        for (ExamPaperQuestion relation : relations) {
            StudentExamAnswer detail = answerMap.get(relation.getQuestionId());
            if (detail == null) {
                detail = new StudentExamAnswer();
                detail.setRecordId(record.getId());
                detail.setQuestionId(relation.getQuestionId());
                detail.setStudentAnswer(null);
                studentExamAnswerMapper.insert(detail);
            }

            Question question = questionMap.get(relation.getQuestionId());
            boolean correct =
                    question != null
                            && question.getAnswer() != null
                            && detail.getStudentAnswer() != null
                            && question.getAnswer().trim()
                                    .equalsIgnoreCase(detail.getStudentAnswer().trim());
            detail.setIsCorrect(correct ? 1 : 0);
            detail.setScore(correct ? relation.getScore() : 0);
            studentExamAnswerMapper.updateById(detail);
            totalScore += detail.getScore() == null ? 0 : detail.getScore();
        }

        record.setStatus(1);
        record.setSubmitTime(new Date());
        record.setTotalScore(totalScore);
        studentExamRecordMapper.updateById(record);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", record.getId());
        data.put("totalScore", totalScore);
        data.put("scoreVisible", getRuntimeConfig(examId).getScoreVisible() == 1);
        data.put("answerVisible", getRuntimeConfig(examId).getAnswerVisible() == 1);
        return data;
    }

    /**
     * 由 Go Proctor gRPC 或 Redis 评分队列触发的评分入口。
     * 接收原始答案列表，完成评分、答案入库和记录状态更新。
     *
     * @param examId     试卷 ID
     * @param studentId  学生 ID
     * @param recordId   考试记录 ID
     * @param answers    学生作答列表（questionId -> studentAnswer）
     * @param submitType 0=主动交卷, 1=超时强制交卷
     * @return 包含 recordId 和 totalScore 的结果 Map
     */
    public Map<String, Object> gradeExamFromProctor(
            Long examId,
            Long studentId,
            Long recordId,
            List<AnswerPayload> answers) {
        StudentExamRecord record = studentExamRecordMapper.selectById(recordId);
        if (record == null) {
            throw ApiBusinessException.notFound("考试记录不存在: recordId=" + recordId);
        }
        if (!Objects.equals(record.getStudentId(), studentId)) {
            throw ApiBusinessException.forbidden("无权操作其他学生的考试记录");
        }
        if (!Objects.equals(record.getExamId(), examId)) {
            throw ApiBusinessException.unprocessable("考试记录与试卷不匹配");
        }
        if (!Objects.equals(record.getStatus(), 0)) {
            throw ApiBusinessException.conflict("该考试记录已提交，不能重复评分");
        }

        List<ExamPaperQuestion> relations = loadPaperRelationsOrThrow(examId);
        Set<Long> allowedQuestionIds =
                relations.stream().map(ExamPaperQuestion::getQuestionId).collect(Collectors.toSet());
        validateAnswers(answers, allowedQuestionIds);
        syncAnswers(recordId, answers);

        Map<Long, StudentExamAnswer> answerMap = loadAnswerDetails(recordId);
        List<Long> paperQuestionIds = relations.stream().map(ExamPaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(paperQuestionIds)
                .stream()
                .collect(Collectors.toMap(Question::getId, question -> question));
        if (questionMap.size() != paperQuestionIds.size()) {
            List<Long> missingQuestionIds = new ArrayList<>();
            for (Long questionId : paperQuestionIds) {
                if (!questionMap.containsKey(questionId)) {
                    missingQuestionIds.add(questionId);
                }
            }
            throw ApiBusinessException.unprocessable(
                    "试卷包含已删除或不存在的题目，无法交卷: " + missingQuestionIds);
        }

        int totalScore = 0;
        for (ExamPaperQuestion relation : relations) {
            StudentExamAnswer detail = answerMap.get(relation.getQuestionId());
            if (detail == null) {
                detail = new StudentExamAnswer();
                detail.setRecordId(recordId);
                detail.setQuestionId(relation.getQuestionId());
                detail.setStudentAnswer(null);
                studentExamAnswerMapper.insert(detail);
            }

            Question question = questionMap.get(relation.getQuestionId());
            boolean correct =
                    question != null
                            && question.getAnswer() != null
                            && detail.getStudentAnswer() != null
                            && question.getAnswer().trim()
                                    .equalsIgnoreCase(detail.getStudentAnswer().trim());
            detail.setIsCorrect(correct ? 1 : 0);
            detail.setScore(correct ? relation.getScore() : 0);
            studentExamAnswerMapper.updateById(detail);
            totalScore += detail.getScore() == null ? 0 : detail.getScore();
        }

        record.setStatus(1);
        record.setSubmitTime(new Date());
        record.setTotalScore(totalScore);
        studentExamRecordMapper.updateById(record);
        cleanupRedisSession(examId, studentId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", recordId);
        data.put("totalScore", totalScore);
        return data;
    }

    private Map<String, Object> getExamRecordReview(StorageCommand command) {
        Long examId = getOptionalLong(command.getPayload(), "examId");
        Long recordId = getOptionalLong(command.getPayload(), "recordId");
        Long currentUserId = command.getOperatorId();
        boolean teacher = isTeacher(command);
        if (teacher && examId == null && recordId == null) {
            throw ApiBusinessException.badRequest("教师查看作答记录时必须提供 examId 或 recordId");
        }

        StudentExamRecord record = resolveRecord(recordId, examId, currentUserId, teacher);
        ExamPaper paper = loadExamPaperOrThrow(record.getExamId());
        if (teacher && !Objects.equals(paper.getCreatorId(), currentUserId)) {
            throw ApiBusinessException.forbidden("无权查看其他教师创建考试的作答记录");
        }
        ExamRuntimeConfig config = getRuntimeConfig(record.getExamId());
        boolean scoreVisible = teacher || config.getScoreVisible() == 1;
        boolean answerVisible = teacher || config.getAnswerVisible() == 1;

        List<ExamPaperQuestion> relations = loadPaperRelationsOrThrow(record.getExamId());
        Map<Long, StudentExamAnswer> answers = loadAnswerDetails(record.getId());
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(
                        relations.stream().map(ExamPaperQuestion::getQuestionId).toList())
                .stream()
                .collect(Collectors.toMap(Question::getId, question -> question));

        List<Map<String, Object>> questionDetails = new ArrayList<>();
        for (ExamPaperQuestion relation : relations) {
            Question question = questionMap.get(relation.getQuestionId());
            StudentExamAnswer answer = answers.get(relation.getQuestionId());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("questionId", relation.getQuestionId());
            detail.put("sortOrder", relation.getSortOrder());
            detail.put("type", question == null ? null : question.getType());
            detail.put("stem", question == null ? null : question.getStem());
            detail.put("options", question == null ? List.of() : question.getOptions());
            detail.put("studentAnswer", answer == null ? null : answer.getStudentAnswer());
            detail.put("score", scoreVisible && answer != null ? answer.getScore() : null);
            detail.put("isCorrect", scoreVisible && answer != null ? answer.getIsCorrect() : null);
            detail.put(
                    "standardAnswer",
                    answerVisible && question != null ? question.getAnswer() : null);
            questionDetails.add(detail);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", record.getId());
        data.put("examId", record.getExamId());
        data.put("examTitle", paper == null ? null : paper.getTitle());
        data.put("studentId", record.getStudentId());
        data.put("submitTime", record.getSubmitTime());
        data.put("totalScore", scoreVisible ? record.getTotalScore() : null);
        data.put("scoreVisible", scoreVisible);
        data.put("answerVisible", answerVisible);
        data.put("questions", questionDetails);
        return data;
    }

    private Map<String, Object> listMyExamRecords(StorageCommand command) {
        requireStudent(command);

        int current = getInteger(command.getPayload(), "current", 1);
        int size = getInteger(command.getPayload(), "size", 10);
        validatePageParams(current, size);
        Long studentId = command.getOperatorId();

        // 1. 查所有已发布且在有效期内的试卷
        QueryWrapper<ExamPaper> paperWrapper = new QueryWrapper<>();
        paperWrapper.eq("status", 1)
                .and(w -> w.isNull("valid_end_time").or().gt("valid_end_time", new Date()))
                .orderByDesc("create_time");
        Page<ExamPaper> paperPage = examPaperMapper.selectPage(new Page<>(current, size), paperWrapper);

        // 2. 批量查该学生对应这些试卷的考试记录
        Map<Long, StudentExamRecord> recordMap = new HashMap<>();
        List<Long> examIds = paperPage.getRecords().stream().map(ExamPaper::getId).toList();
        if (!examIds.isEmpty()) {
            QueryWrapper<StudentExamRecord> recordWrapper = new QueryWrapper<>();
            recordWrapper.eq("student_id", studentId).in("exam_id", examIds);
            List<StudentExamRecord> records = studentExamRecordMapper.selectList(recordWrapper);
            recordMap = records.stream()
                    .collect(Collectors.toMap(StudentExamRecord::getExamId, r -> r, (a, b) -> a));
        }

        // 3. 组装返回数据
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExamPaper paper : paperPage.getRecords()) {
            StudentExamRecord record = recordMap.get(paper.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("examId", paper.getId());
            item.put("examTitle", paper.getTitle());
            item.put("totalScore", paper.getTotalScore());
            item.put("durationMins", paper.getDurationMins());
            item.put("validStartTime", paper.getValidStartTime());
            item.put("validEndTime", paper.getValidEndTime());
            item.put("paperStatus", paper.getStatus());
            item.put("recordId", record == null ? null : record.getId());
            item.put("recordStatus", record == null ? null : record.getStatus());
            item.put("studentScore", record == null ? null : record.getTotalScore());
            item.put("submitTime", record == null ? null : record.getSubmitTime());
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", items);
        result.put("total", paperPage.getTotal());
        result.put("size", paperPage.getSize());
        result.put("current", paperPage.getCurrent());
        result.put("pages", paperPage.getPages());
        return result;
    }

    private StudentExamRecord resolveDraftRecord(
            Long examId, Long studentId, Map<String, Object> payload, boolean allowCreate) {
        ExamPaper paper = requireAccessibleExamForStudent(examId);
        ExamRuntimeConfig config = getRuntimeConfig(examId);
        Long recordId = getOptionalLong(payload, "recordId");
        if (recordId != null) {
            StudentExamRecord record = studentExamRecordMapper.selectById(recordId);
            if (record == null) {
                throw ApiBusinessException.notFound("考试记录不存在或已删除");
            }
            validateDraftRecord(record, examId, studentId);
            return record;
        }
        StudentExamRecord record = findDraftRecord(examId, studentId);
        if (record != null) {
            return record;
        }
        if (!allowCreate) {
            if (countSubmittedAttempts(examId, studentId) >= config.getAllowedAttempts()) {
                throw ApiBusinessException.conflict("已达到允许考试次数上限");
            }
            throw ApiBusinessException.notFound("未找到进行中的考试记录，请先开始考试");
        }
        if (countSubmittedAttempts(examId, studentId) >= config.getAllowedAttempts()) {
            throw ApiBusinessException.conflict("已达到允许考试次数上限");
        }

        record = new StudentExamRecord();
        record.setExamId(examId);
        record.setStudentId(studentId);
        record.setCourseId(paper.getCourseId());
        record.setStatus(0);
        record.setTotalScore(0);
        studentExamRecordMapper.insert(record);
        return record;
    }

    private StudentExamRecord findDraftRecord(Long examId, Long studentId) {
        QueryWrapper<StudentExamRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("exam_id", examId)
                .eq("student_id", studentId)
                .eq("status", 0)
                .orderByDesc("create_time")
                .last("LIMIT 1");
        return studentExamRecordMapper.selectOne(wrapper);
    }

    private StudentExamRecord resolveRecord(
            Long recordId, Long examId, Long currentUserId, boolean teacher) {
        StudentExamRecord record = null;
        if (recordId != null) {
            record = studentExamRecordMapper.selectById(recordId);
            if (record == null) {
                throw ApiBusinessException.notFound("未找到对应的作答记录");
            }
            if (examId != null && !Objects.equals(record.getExamId(), examId)) {
                throw ApiBusinessException.unprocessable("recordId 与 examId 不匹配");
            }
        }
        if (record == null) {
            QueryWrapper<StudentExamRecord> wrapper = new QueryWrapper<>();
            if (examId != null) {
                wrapper.eq("exam_id", examId);
            }
            if (!teacher) {
                wrapper.eq("student_id", currentUserId);
            }
            wrapper.eq("status", 1).orderByDesc("submit_time").last("LIMIT 1");
            record = studentExamRecordMapper.selectOne(wrapper);
        }
        if (record == null) {
            throw ApiBusinessException.notFound("未找到对应的作答记录");
        }
        if (!Objects.equals(record.getStatus(), 1)) {
            throw ApiBusinessException.conflict("该考试记录尚未交卷");
        }
        if (!teacher && !currentUserId.equals(record.getStudentId())) {
            throw ApiBusinessException.forbidden("无权查看其他学生的作答记录");
        }
        return record;
    }

    private void syncAnswers(Long recordId, List<AnswerPayload> answers) {
        Map<Long, StudentExamAnswer> existing = loadAnswerDetails(recordId);
        for (AnswerPayload payload : answers) {
            StudentExamAnswer answer = existing.get(payload.getQuestionId());
            if (answer == null) {
                answer = new StudentExamAnswer();
                answer.setRecordId(recordId);
                answer.setQuestionId(payload.getQuestionId());
                answer.setStudentAnswer(payload.getStudentAnswer());
                studentExamAnswerMapper.insert(answer);
            } else {
                answer.setStudentAnswer(payload.getStudentAnswer());
                studentExamAnswerMapper.updateById(answer);
            }
        }
    }

    private void validateAnswers(List<AnswerPayload> answers, Set<Long> paperQuestionIds) {
        Set<Long> seenQuestionIds = new HashSet<>();
        for (AnswerPayload payload : answers) {
            if (payload == null) {
                throw ApiBusinessException.badRequest("answers 中不能包含空对象");
            }
            Long questionId = payload.getQuestionId();
            if (questionId == null || questionId <= 0) {
                throw ApiBusinessException.badRequest("answers.questionId 必须为正整数");
            }
            if (!seenQuestionIds.add(questionId)) {
                throw ApiBusinessException.conflict("答案列表中存在重复的 questionId: " + questionId);
            }
            if (!paperQuestionIds.contains(questionId)) {
                throw ApiBusinessException.unprocessable("题目 " + questionId + " 不属于当前试卷");
            }
            if (payload.getStudentAnswer() != null) {
                String trimmedAnswer = payload.getStudentAnswer().trim();
                payload.setStudentAnswer(trimmedAnswer.isEmpty() ? null : trimmedAnswer);
                if (trimmedAnswer.length() > 1024) {
                    throw ApiBusinessException.unprocessable(
                            "题目 " + questionId + " 的作答内容长度不能超过 1024");
                }
            }
        }
    }

    private Map<Long, String> loadAnswerMap(Long recordId) {
        QueryWrapper<StudentExamAnswer> wrapper = new QueryWrapper<>();
        wrapper.eq("record_id", recordId);
        return studentExamAnswerMapper.selectList(wrapper).stream()
                .collect(
                        Collectors.toMap(
                                StudentExamAnswer::getQuestionId,
                                StudentExamAnswer::getStudentAnswer,
                                (left, right) -> right,
                                LinkedHashMap::new));
    }

    private Map<Long, StudentExamAnswer> loadAnswerDetails(Long recordId) {
        QueryWrapper<StudentExamAnswer> wrapper = new QueryWrapper<>();
        wrapper.eq("record_id", recordId);
        return studentExamAnswerMapper.selectList(wrapper).stream()
                .collect(
                        Collectors.toMap(
                                StudentExamAnswer::getQuestionId,
                                detail -> detail,
                                (left, right) -> right,
                                LinkedHashMap::new));
    }

    private int countSubmittedAttempts(Long examId, Long studentId) {
        QueryWrapper<StudentExamRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("exam_id", examId).eq("student_id", studentId).eq("status", 1);
        Long count = studentExamRecordMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    private ExamPaper requireAccessibleExamForStudent(Long examId) {
        examPaperService.getExamPaperForStudent(examId);
        return loadExamPaperOrThrow(examId);
    }

    private ExamPaper loadExamPaperOrThrow(Long examId) {
        ExamPaper paper = examPaperMapper.selectById(examId);
        if (paper == null) {
            throw ApiBusinessException.notFound("试卷不存在或已删除");
        }
        return paper;
    }

    private List<ExamPaperQuestion> loadPaperRelationsOrThrow(Long examId) {
        QueryWrapper<ExamPaperQuestion> relationWrapper = new QueryWrapper<>();
        relationWrapper.eq("paper_id", examId).orderByAsc("sort_order");
        List<ExamPaperQuestion> relations = examPaperQuestionMapper.selectList(relationWrapper);
        if (relations.isEmpty()) {
            throw ApiBusinessException.unprocessable("试卷未配置题目");
        }
        return relations;
    }

    private Set<Long> loadPaperQuestionIds(Long examId) {
        return loadPaperRelationsOrThrow(examId).stream()
                .map(ExamPaperQuestion::getQuestionId)
                .collect(Collectors.toSet());
    }

    private void publishExamPaper(Long examId) {
        if (examPaperRedisPublisher != null) {
            examPaperRedisPublisher.publishExamPaper(examId);
        }
    }

    private ProctorControllerClient.ProctorStartResult startProctorIfEnabled(Long examId) {
        if (proctorControllerClient == null) {
            return null;
        }
        return proctorControllerClient.startProctor(examId);
    }

    private void cleanupRedisSession(Long examId, Long studentId) {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.delete(List.of(
                "exam:" + examId + ":answers:" + studentId,
                "exam:" + examId + ":session:" + studentId));
    }

    private ExamRuntimeConfig getRuntimeConfig(Long examId) {
        ExamRuntimeConfig config = examRuntimeConfigMapper.selectById(examId);
        if (config == null) {
            config = new ExamRuntimeConfig();
            config.setExamId(examId);
            config.setAllowedAttempts(1);
            config.setScoreVisible(0);
            config.setAnswerVisible(0);
            config.setScoringStrategy("AUTO_GRADE");
            return config;
        }
        if (config.getAllowedAttempts() == null || config.getAllowedAttempts() <= 0) {
            config.setAllowedAttempts(1);
        }
        return config;
    }

    private List<AnswerPayload> readAnswers(Map<String, Object> payload) {
        Object value = payload.get("answers");
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, new TypeReference<List<AnswerPayload>>() {});
    }

    private void requireStudent(StorageCommand command) {
        if (!isStudent(command)) {
            throw ApiBusinessException.forbidden("该接口仅允许学生使用");
        }
    }

    private boolean isStudent(StorageCommand command) {
        return command.getOperatorRole() != null
                && "STUDENT".equalsIgnoreCase(command.getOperatorRole());
    }

    private boolean isTeacher(StorageCommand command) {
        return command.getOperatorRole() != null
                && "TEACHER".equalsIgnoreCase(command.getOperatorRole());
    }

    private Long getLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw ApiBusinessException.badRequest("缺少参数: " + key);
        }
        try {
            long result = Long.parseLong(value.toString());
            if (result <= 0) {
                throw ApiBusinessException.badRequest(key + " 必须为正整数");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为正整数");
        }
    }

    private Long getOptionalLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            long result = Long.parseLong(value.toString());
            if (result <= 0) {
                throw ApiBusinessException.badRequest(key + " 必须为正整数");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为正整数");
        }
    }

    private int getInteger(Map<String, Object> payload, String key, int defaultValue) {
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为整数");
        }
    }

    private void validateDraftRecord(StudentExamRecord record, Long examId, Long studentId) {
        if (!Objects.equals(record.getStudentId(), studentId)) {
            throw ApiBusinessException.forbidden("无权操作其他学生的考试记录");
        }
        if (!Objects.equals(record.getExamId(), examId)) {
            throw ApiBusinessException.unprocessable("考试记录与试卷不匹配");
        }
        if (!Objects.equals(record.getStatus(), 0)) {
            throw ApiBusinessException.conflict("该考试记录已提交，不能重复保存或交卷");
        }
    }

    private void validatePageParams(int current, int size) {
        if (current <= 0) {
            throw ApiBusinessException.badRequest("分页页码必须大于 0");
        }
        if (size <= 0) {
            throw ApiBusinessException.badRequest("分页大小必须大于 0");
        }
        if (size > 100) {
            throw ApiBusinessException.badRequest("分页大小不能超过 100");
        }
    }

    private Long requireOperatorId(StorageCommand command) {
        if (command.getOperatorId() == null || command.getOperatorId() <= 0) {
            throw ApiBusinessException.forbidden("当前学生身份缺失");
        }
        return command.getOperatorId();
    }

    public static class AnswerPayload {
        private Long questionId;
        private String studentAnswer;

        public Long getQuestionId() {
            return questionId;
        }

        public void setQuestionId(Long questionId) {
            this.questionId = questionId;
        }

        public String getStudentAnswer() {
            return studentAnswer;
        }

        public void setStudentAnswer(String studentAnswer) {
            this.studentAnswer = studentAnswer;
        }
    }
}
