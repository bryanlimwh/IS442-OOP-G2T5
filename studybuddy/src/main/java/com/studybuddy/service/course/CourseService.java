package com.studybuddy.service.course;

import java.util.List;

import org.springframework.stereotype.Service;

import com.studybuddy.entity.Course;
import com.studybuddy.exception.CourseNotFoundException;
import com.studybuddy.repository.CourseRepository;

@Service
public class CourseService {

    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public Course createCourse(String courseCode, String courseName) {
        Course course = new Course(courseCode, courseName);
        return courseRepository.save(course);
    }

    public Course getCourseById(Long courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));
    }

    public List<Course> getAllCourses() {
        return courseRepository.findAll();
    }

    public Course updateCourse(Long courseId, String newCourseCode, String newCourseName) {
        Course course = getCourseById(courseId);
        course.setCourseCode(newCourseCode);
        course.setCourseName(newCourseName);
        return courseRepository.save(course);
    }

    public void deleteCourse(Long courseId) {
        Course course = getCourseById(courseId);
        courseRepository.delete(course);
    }
}