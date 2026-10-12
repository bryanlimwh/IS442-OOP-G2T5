package com.studybuddy.service.studybuddymatching;

import java.util.List;

import org.springframework.stereotype.Service;

import com.studybuddy.entity.Student;
import com.studybuddy.entity.enums.MatchRequestStatus;
import com.studybuddy.entity.MatchRequest;
import com.studybuddy.exception.DuplicateMatchRequestException;
import com.studybuddy.exception.MatchRequestNotFoundException;
import com.studybuddy.exception.NotMatchRequestParticipantException;
import com.studybuddy.exception.StudentNotFoundException;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.StudentRepository;

import org.springframework.transaction.annotation.Transactional;

/**
 * Sending, viewing and accepting study-buddy match requests.
 * <p>
 * Security rules applied here (not in the controller, so they can't be skipped):
 * <ul>
 * <li>The student is always identified by the email they logged in with, never by
 *     an id typed into the URL. Otherwise anyone could change the id and act as,
 *     or read the requests of, another student.</li>
 * <li>A request can only be viewed by its sender or recipient, and only accepted by
 *     its recipient. Anyone else gets NotMatchRequestParticipantException (403).</li>
 * <li>Requests are returned as MatchRequestSummary, never as the entity, so contact
 *     numbers and password hashes are not sent back (see MatchRequestSummary).</li>
 * </ul>
 */
@Service
public class MatchRequestService {

    // Stores the object containing the reference
    // to the 'match_request' table in the H2 database
    private final MatchRequestRepository matchrequestRepository;

    // Used to look up the logged-in student (by email) and the recipient (by id)
    private final StudentRepository studentRepository;

    // Spring's IoC container (the ApplicationContext) will create the repository objects automatically
    public MatchRequestService(MatchRequestRepository repo, StudentRepository studentRepository) {
        this.matchrequestRepository = repo;
        this.studentRepository = studentRepository;
    }



    // Retrieves the logged-in student's information with the email they logged in with
    // Returns a Student object 
    private Student findStudentByEmail(String email) {
        return studentRepository.findByEmail(email).orElseThrow(() -> new StudentNotFoundException(email));
    }

    // @Transactional specified to treat all H2 database operations
    // executed within the method as a single transaction,
    // meaning that all database changes made during the execution of the method
    // are rolled back if any one of its database operations fails

    // Creates a new entry in the 'match_request' table.
    // The sender is the logged-in student (senderEmail comes from their login session),
    // so a student can only send requests as themselves.
    @Transactional
    // Returns a summary of the saved request (including its generated id).
    // Any failure is thrown as an exception instead of returning false,
    // so the controller can turn it into the correct HTTP error status.
    public MatchRequestSummary createRequest(String senderEmail, Long recipientId, String message) {
        Student sender = findStudentByEmail(senderEmail);
        Student recipient = studentRepository.findById(recipientId)
                .orElseThrow(() -> new StudentNotFoundException(recipientId));

        // Checks if the sender and recipient are the same student
        if (sender.equals(recipient)) {
            throw new IllegalArgumentException("Same sender and recipient");
        }

        // Rule: only 1 PENDING request may exist between two students, in either direction

        // Case 1: this sender already sent this recipient a request that is still PENDING
        if (matchrequestRepository.existsBySender_userIdAndRecipient_userIdAndStatus(
                sender.getUserId(), recipient.getUserId(), MatchRequestStatus.PENDING)) {
            throw DuplicateMatchRequestException.alreadySent(sender.getUserId(), recipient.getUserId());
        }

        // Case 2: the recipient already sent this sender a PENDING request
        if (matchrequestRepository.existsBySender_userIdAndRecipient_userIdAndStatus(
                recipient.getUserId(), sender.getUserId(), MatchRequestStatus.PENDING)) {
            throw DuplicateMatchRequestException.alreadyReceived(sender.getUserId(), recipient.getUserId());
        }

        // TO IMPLEMENT (once StudyBuddyConnection exists):
        // Block the request if the two students already have a StudyBuddyConnection

        MatchRequest req = new MatchRequest(sender, recipient, message);

        // INSERTs the new row into 'match_request'; save() returns it with its generated id.
        // The summary is built from the sender's point of view (the logged-in student),
        // so the recipient's contact number stays hidden while the request is PENDING.
        return MatchRequestSummary.from(matchrequestRepository.save(req), sender);
    }

    // Retrieves the match request with the given id,
    // but only if the logged-in student is its sender or recipient.
    @Transactional
    public MatchRequestSummary getRequestById(String studentEmail, Long requestId) {
        Student student = findStudentByEmail(studentEmail);

        // Load the row from the 'match_request' table where 'id' = requestId
        // as a Java object (SELECT ... WHERE id = ?)
        MatchRequest req = matchrequestRepository.findById(requestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(requestId));

        // Allow access only if the student is the sender OR the recipient.
        // (Compared with equals(), which compares user ids; != on two Long objects
        // compares whether they are the same object in memory, not the same number.)
        boolean isSender = req.getSender().equals(student);
        boolean isRecipient = req.getRecipient().equals(student);
        if (!isSender && !isRecipient) {
            throw new NotMatchRequestParticipantException(requestId, student.getUserId());
        }

        return MatchRequestSummary.from(req, student);
    }

    // Retrieves every match request the logged-in student has sent
    // (all rows in the 'match_request' table where 'sender_id' = their id).
    // An empty list simply means they haven't sent any yet.
    @Transactional
    public List<MatchRequestSummary> getMatchRequestBySender(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        return matchrequestRepository.findBySender_userId(student.getUserId()).stream()
                .map(req -> MatchRequestSummary.from(req, student))
                .toList();
    }

    // Retrieves every request the logged-in student has received
    // (all rows in the 'match_request' table where 'recipient_id' = their id).
    // An empty list simply means they haven't received any yet.
    @Transactional
    public List<MatchRequestSummary> getMatchRequestByRecipient(String studentEmail) {
        Student student = findStudentByEmail(studentEmail);
        return matchrequestRepository.findByRecipient_userId(student.getUserId()).stream()
                .map(req -> MatchRequestSummary.from(req, student))
                .toList();
    }

    // Updates the 'status' column match request record in the 'match_request' table to "ACCEPTED".
    // Only the student the request was sent to may accept it.
    @Transactional
    // Called by MatchRequestController when the URL to accept match requests is accessed
    // UPDATE the row in the 'match_request' table whose 'id' = requestId
    public MatchRequestSummary acceptRequest(String studentEmail, Long requestId) {
        Student student = findStudentByEmail(studentEmail);

        // 1. Load the row from H2 as a Java object (SELECT ... WHERE id = ?)
        MatchRequest req = matchrequestRepository.findById(requestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(requestId));

        // 2. Stop anyone other than the recipient from accepting it
        //    (including the sender, who can't accept their own request).
        if (!req.getRecipient().equals(student)) {
            throw new NotMatchRequestParticipantException(requestId, student.getUserId());
        }

        // 3. Apply the business rule; the entity checks that the change is valid
        req.accept();

        // 4. Optional: dirty checking would save it anyway, but calling save() makes the intent clear
        // Hibernate runs: UPDATE match_request SET status='ACCEPTED' WHERE id=?
        // The returned summary now includes the sender's contact number, since the request is accepted.
        return MatchRequestSummary.from(matchrequestRepository.save(req), student);

        // TO IMPLEMENT:
        // Create a new StudyBuddyConnection and save it to the database
        //
        // StudyBuddyConnection connectionEntry = new StudyBuddyConnection(
        //     this.sender,
        //     this.recipient
        // );
    }


    @Transactional
    public MatchRequestSummary rejectRequest(String studentEmail, Long requestId) {
        Student student = findStudentByEmail(studentEmail);

        // Loads the row from H2 as a MatchRequest Java object (SELECT ... WHERE id = ?)
        MatchRequest req = matchrequestRepository.findById(requestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(requestId));
        
        // Verifies that the student identity attempting to reject the
        // indicated match request is the recipient of the match requests
        // Throws an exception if anyone other than the match request's recipient attempts to reject it
        if (!req.getRecipient().equals(student)) {
            throw new NotMatchRequestParticipantException(requestId, student.getUserId());
        }

        // Rejects the match request
        req.reject();

        // Explicitly calls save() to update the row in H2
        // Hibernate runs: UPDATE match_request SET status='REJECTED' WHERE id=?
        // Optional to do so as dirty checking would perform this save to H2 anyway

        // Returns the match request summary now reflecting the match request status as "REJECTED".
        return MatchRequestSummary.from(matchrequestRepository.save(req), student);
    }
}
