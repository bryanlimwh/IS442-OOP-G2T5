package com.studybuddy.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import com.studybuddy.entity.enums.MatchingStrategyType;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyPreferenceRepository;
import com.studybuddy.service.matching.MatchingConfigService;
import com.studybuddy.service.matching.MatchingService;

/**
 * The interchangeable matching strategies (Strategy pattern): every strategy type the admin can
 * pick has exactly one @Component, and MatchingService refuses to start without one for every
 * type. No database and no Spring context: the classes are found by scanning the code, and
 * MatchingService is built by hand with mocked repositories.
 */
class MatchingStrategyComponentsTest {

    // D2, A4: each enum value maps to exactly one strategy component
    @Test
    void everyStrategyTypeHasExactlyOneComponent() throws Exception {
        Map<MatchingStrategyType, List<String>> classesByType = new EnumMap<>(MatchingStrategyType.class);
        for (MatchingStrategy strategy : scannedStrategies()) {
            classesByType.computeIfAbsent(strategy.getType(), type -> new ArrayList<>())
                    .add(strategy.getClass().getSimpleName());
        }

        assertThat(classesByType.keySet()).containsExactlyInAnyOrder(MatchingStrategyType.values());
        assertThat(classesByType).allSatisfy((type, classes) -> assertThat(classes).as(type.name()).hasSize(1));
        assertThat(classesByType).containsEntry(MatchingStrategyType.BALANCED, List.of("BalancedMatchingStrategy"))
                .containsEntry(MatchingStrategyType.AVAILABILITY_FIRST, List.of("AvailabilityFirstMatchingStrategy"))
                .containsEntry(MatchingStrategyType.COURSE_FIRST, List.of("CourseFirstMatchingStrategy"));
    }

    // D2: MatchingService starts with every strategy the app has
    @Test
    void matchingServiceStartsWithAllStrategies() throws Exception {
        List<MatchingStrategy> strategies = scannedStrategies();

        assertThat(newMatchingService(strategies)).isNotNull();
    }

    // D2: a strategy type without an implementation stops the app at startup, not at the first search
    @Test
    void matchingServiceRefusesToStartWithAMissingStrategy() {
        List<MatchingStrategy> withoutCourseFirst = List.of(new BalancedMatchingStrategy(),
                new AvailabilityFirstMatchingStrategy());

        assertThatThrownBy(() -> newMatchingService(withoutCourseFirst))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No MatchingStrategy implementation for COURSE_FIRST");
    }

    // Every concrete MatchingStrategy class in the app that is a Spring @Component, one instance each
    private static List<MatchingStrategy> scannedStrategies() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(MatchingStrategy.class));
        List<MatchingStrategy> strategies = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.studybuddy")) {
            assertThat(((AnnotatedBeanDefinition) candidate).getMetadata().hasAnnotation(Component.class.getName()))
                    .as(candidate.getBeanClassName() + " is a @Component").isTrue();
            strategies.add((MatchingStrategy) Class.forName(candidate.getBeanClassName())
                    .getDeclaredConstructor().newInstance());
        }
        return strategies;
    }

    private static MatchingService newMatchingService(List<MatchingStrategy> strategies) {
        return new MatchingService(mock(StudentRepository.class), mock(StudyPreferenceRepository.class),
                mock(MatchingConfigService.class), new CompatibilityCalculator(0.8), strategies);
    }
}
