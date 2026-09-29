package com.studybuddy.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.studybuddy.entity.enums.Role;

import jakarta.persistence.CascadeType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * A student account. Stored in the 'student' table
 * Methods here only touch the student's own fields; anything needing a repository or another aggregate belongs in a service.
 */

@Entity
@Table(name = "student") 
// The student's corresponding row in the 'User' table is tagged with user_type = 'STUDENT'
@DiscriminatorValue("STUDENT")
public class Student extends User {

    private String name;
    private String school;
    private String programme;
    private int yearOfStudy;
    private String contactNumber;

    // Many-to-many: bridged via a student_course join table.
    @ManyToMany
    @JoinTable(name = "student_course",
            joinColumns = @JoinColumn(name = "student_id"),
            inverseJoinColumns = @JoinColumn(name = "course_id"))
    private List<Course> coursesTaken = new ArrayList<>();

    // One-to-many: inverse side of StudyPreference.student, which owns the
    // student_id FK. No join table needed.
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StudyPreference> preferences = new ArrayList<>();

    // One-to-many: inverse side of StudyGroup.leader, which owns the leader_id FK.
    // Only StudyGroup's constructor adds to it, via addGroupLed().
    @OneToMany(mappedBy = "leader")
    private List<StudyGroup> groupsLed = new ArrayList<>();

    /**
     * No-arg constructor required by JPA.
     */
    protected Student() {
        super();
    }

    // Real constructor
    public Student(String email, String passwordHash, String name, String school,
            String programme, int yearOfStudy, String contactNumber) {
        super(email, passwordHash);
        this.name = name;
        this.school = school;
        this.programme = programme;
        this.yearOfStudy = yearOfStudy;
        this.contactNumber = contactNumber;
    }

    // ---- Behaviour ----

    public void addPreference(StudyPreference pref) {
        pref.setStudent(this);
        preferences.add(pref);
    }

    public void addCourse(Course course) {
        coursesTaken.add(course);
    }

    // Keeps this side in step with StudyGroup.leader.
    // Package-private (no "public") so only StudyGroup's constructor can call it, which means a group is
    // only ever added for its real leader. If it were public, a caller could add a group this student
    // doesn't lead: isLeader() would wrongly return true, and because groupsLed is the mappedBy side,
    // Hibernate wouldn't save it, so the group would vanish from the list on the next load.
    void addGroupLed(StudyGroup group) {
        groupsLed.add(group);
    }

    // Computed rather than stored, so it can never disagree with groupsLed.
    // A student whose groups are all closed still counts as a leader.
    public boolean isLeader() {
        return !groupsLed.isEmpty();
    }

    public boolean isLeaderOf(StudyGroup group) {
        return group.isLedBy(this);
    }

    // Implements User's abstract methods so Admin and Student can each answer differently for any code holding a plain User reference.
    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public Role getRole() {
        return Role.STUDENT;
    }

    // ---- Getters / Setters (no Lombok in this project) ----

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchool() {
        return school;
    }

    public void setSchool(String school) {
        this.school = school;
    }

    public String getProgramme() {
        return programme;
    }

    public void setProgramme(String programme) {
        this.programme = programme;
    }

    public int getYearOfStudy() {
        return yearOfStudy;
    }

    public void setYearOfStudy(int yearOfStudy) {
        this.yearOfStudy = yearOfStudy;
    }

    public String getContactNumber() {
        return contactNumber;
    }

    public void setContactNumber(String contactNumber) {
        this.contactNumber = contactNumber;
    }

    public List<Course> getCoursesTaken() {
        return coursesTaken;
    }

    public List<StudyPreference> getPreferences() {
        return preferences;
    }

    // Read-only: new groups are added through the StudyGroup constructor.
    public List<StudyGroup> getGroupsLed() {
        return Collections.unmodifiableList(groupsLed);
    }
}
