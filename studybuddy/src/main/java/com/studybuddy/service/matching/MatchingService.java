package com.studybuddy.service.matching;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.domain.MatchingConfig;
import com.studybuddy.domain.Student;
import com.studybuddy.domain.StudyPreference;
import com.studybuddy.domain.enums.MatchingStrategyType;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.matching.CompatibilityCalculator;
import com.studybuddy.matching.MatchResult;
import com.studybuddy.matching.MatchSearch;
import com.studybuddy.matching.MatchingStrategy;
import com.studybuddy.matching.PreferenceSummary;
import com.studybuddy.matching.ScoreBreakdown;
import com.studybuddy.matching.StudentPublicProfile;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyPreferenceRepository;

/**
 * Finds and ranks potential study buddies for a student.
 * <p>
 * Every other active student's preferences are scored against the searcher's
 * (only the one for the chosen course, if a course was picked). Two
 * preferences for the same course are compared with each other; a preference
 * for a course the searcher has no preference for is compared with whichever
 * of the searcher's preferences fits it best, and scores 0 for course. A
 * student who matches through several preferences is listed once, through
 * their best match. The results are then ordered by the strategy the admin
 * chose in MatchingConfig.
 */
@Service
public class MatchingService {

    private final StudentRepository studentRepository;
    private final StudyPreferenceRepository studyPreferenceRepository;
    private final MatchingConfigService matchingConfigService;
    private final CompatibilityCalculator calculator;
    private final Map<MatchingStrategyType, MatchingStrategy> strategiesByType = new EnumMap<>(MatchingStrategyType.class);

    // Spring passes in every MatchingStrategy bean; they are looked up by the type the admin picks.
    public MatchingService(StudentRepository studentRepository, StudyPreferenceRepository studyPreferenceRepository,
            MatchingConfigService matchingConfigService, CompatibilityCalculator calculator,
            List<MatchingStrategy> strategies) {
        this.studentRepository = studentRepository;
        this.studyPreferenceRepository = studyPreferenceRepository;
        this.matchingConfigService = matchingConfigService;
        this.calculator = calculator;
        strategies.forEach(strategy -> strategiesByType.put(strategy.getType(), strategy));
        // Fail at startup, not on an admin's first search, if a strategy type has no implementation.
        for (MatchingStrategyType type : MatchingStrategyType.values()) {
            if (!strategiesByType.containsKey(type)) {
                throw new IllegalStateException("No MatchingStrategy implementation for " + type);
            }
        }
    }

    /**
     * The searcher's own study preferences, which is what they can be matched on.
     */
    @Transactional(readOnly = true)
    public List<PreferenceSummary> getPreferences(String studentEmail) {
        return findStudent(studentEmail).getPreferences().stream()
                .map(PreferenceSummary::from)
                .sorted(Comparator.comparing(PreferenceSummary::courseCode))
                .toList();
    }

    /**
     * Ranked potential study buddies for the student, best match first. Empty
     * if the student has no study preference in scope to match on.
     */
    @Transactional(readOnly = true)
    public List<MatchResult> findMatches(String studentEmail, MatchSearch search) {
        Student searcher = findStudent(studentEmail);
        List<StudyPreference> myPreferences = searcher.getPreferences().stream()
                .filter(search::includesMine)
                .toList();
        MatchingConfig config = matchingConfigService.getCurrent();
        Comparator<MatchResult> ranking = strategiesByType.get(config.getStrategy()).ranking();

        // Best result per student, so nobody is listed twice.
        Map<Long, MatchResult> bestByStudent = new HashMap<>();
        for (StudyPreference theirs : studyPreferenceRepository.findAll()) {
            Student candidate = theirs.getStudent();
            if (candidate.equals(searcher) || !candidate.isActive()) {
                continue;
            }
            for (StudyPreference mine : preferencesToCompareWith(theirs, myPreferences)) {
                ScoreBreakdown breakdown = calculator.score(mine, theirs, config.getTargetSessionMinutes());
                if (search.accepts(theirs, breakdown)) {
                    MatchResult result = new MatchResult(StudentPublicProfile.from(candidate),
                            PreferenceSummary.from(theirs), breakdown, breakdown.weightedScore(config));
                    bestByStudent.merge(candidate.getUserId(), result,
                            (kept, challenger) -> ranking.compare(challenger, kept) < 0 ? challenger : kept);
                }
            }
        }
        return bestByStudent.values().stream().sorted(ranking).toList();
    }

    // Same course if possible. Otherwise e.g. their CS201 preference could be matched against the
    // searcher's IS112 one, and win, even though the searcher also wants a CS201 study buddy.
    private List<StudyPreference> preferencesToCompareWith(StudyPreference theirs, List<StudyPreference> myPreferences) {
        List<StudyPreference> sameCourse = myPreferences.stream()
                .filter(mine -> mine.getCourse().equals(theirs.getCourse()))
                .toList();
        return sameCourse.isEmpty() ? myPreferences : sameCourse;
    }

    private Student findStudent(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}
