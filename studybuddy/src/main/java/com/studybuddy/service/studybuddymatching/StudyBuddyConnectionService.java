package com.studybuddy.service.studybuddymatching;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyBuddyConnection;
import com.studybuddy.exception.ConnectionNotFoundException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyBuddyConnectionRepository;

/**
 * Study-buddy connections: making one when a match request is accepted,
 * listing a student's buddies, and ending a connection.
 * <p>
 * As in MatchRequestService, the student is always identified by their login
 * email, never by an id from the URL, and connections are returned as
 * ConnectionSummary rather than the entity.
 */
@Service
public class StudyBuddyConnectionService {

    private final StudyBuddyConnectionRepository connectionRepository;
    private final StudentRepository studentRepository;

    public StudyBuddyConnectionService(StudyBuddyConnectionRepository connectionRepository,
            StudentRepository studentRepository) {
        this.connectionRepository = connectionRepository;
        this.studentRepository = studentRepository;
    }

    // The pair is unordered, so check both ways round.
    @Transactional(readOnly = true)
    public boolean areConnected(Student a, Student b) {
        return connectionRepository.existsByStudent1_userIdAndStudent2_userId(a.getUserId(), b.getUserId())
                || connectionRepository.existsByStudent1_userIdAndStudent2_userId(b.getUserId(), a.getUserId());
    }

    // Called by MatchRequestService when a request is accepted. Doing nothing when the
    // pair is already connected keeps "one connection per pair" true however it's called.
    @Transactional
    public void connect(Student a, Student b) {
        if (!areConnected(a, b)) {
            connectionRepository.save(new StudyBuddyConnection(a, b));
        }
    }

    // The logged-in student's buddies, newest first.
    @Transactional(readOnly = true)
    public List<ConnectionSummary> getConnections(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        Long id = student.getUserId();
        return connectionRepository.findByStudent1_userIdOrStudent2_userId(id, id).stream()
                .sorted(Comparator.comparing(StudyBuddyConnection::getCreatedAt).reversed())
                .map(connection -> ConnectionSummary.from(connection, student))
                .toList();
    }

    // Either student may end it. Deleting the row is what hides the contact numbers again.
    @Transactional
    public void endConnection(String studentEmail, Long connectionId) {
        Student student = findStudentByEmail(studentEmail);
        StudyBuddyConnection connection = connectionRepository.findById(connectionId)
                .filter(c -> c.involves(student))
                .orElseThrow(() -> new ConnectionNotFoundException(connectionId));
        connectionRepository.delete(connection);
    }

    private Student findStudentByEmail(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }
}
