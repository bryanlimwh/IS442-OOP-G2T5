package com.studybuddy.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-stack flows for study groups: creating, editing and closing a group, asking to join,
 * the leader accepting, rejecting and removing, the waiting list of a full group, cancelling
 * a request and leaving. Replaces MAN-12 to MAN-15 and MAN-31 to MAN-38, MAN-47 and MAN-48.
 */
class StudyGroupFlowTest extends FlowTestSupport {

    private long newCourse(Browser admin) {
        return createCourse(admin, "GRP" + unique());
    }

    /** The leader fills in the create form with one Monday 10:00-12:00 slot; returns the new group's id. */
    private static long createGroup(Browser leader, String name, long courseId, int maxSize) {
        leader.open("/groups/new");
        leader.submit("/groups", "name", name, "courseId", String.valueOf(courseId), "description", "Practice questions",
                "studyGoal", "EXAM_PREPARATION", "studyMode", "ONLINE", "maxSize", String.valueOf(maxSize),
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00");
        assertThat(leader.text()).contains("Group created.");
        return Long.parseLong(leader.url().replaceAll("^/groups/(\\d+).*$", "$1"));
    }

    private static void askToJoin(Browser student, long groupId) {
        student.open("/groups/" + groupId);
        student.submit("/groups/" + groupId + "/join");
        assertThat(student.text()).contains("Request sent. The group's leader will review it.");
    }

    private static void answer(Browser leader, long groupId, String studentName, String action) {
        leader.open("/groups/" + groupId + "/members");
        long membershipId = leader.idAfter(">" + studentName + "<", "/members/(\\d+)/" + action);
        leader.submit("/groups/" + groupId + "/members/" + membershipId + "/" + action);
    }

    // L1, L2, GRP-1, GRP-7, TD-2: a leader creates a group with two slots; it shows its details and other students can find it (MAN-12)
    @Test
    void leaderCreatesAGroupThatOthersCanFind() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Lena Leader");
        String name = "Flow Group " + unique();

        leader.open("/groups/new");
        leader.submit("/groups", "name", name, "courseId", String.valueOf(courseId), "description", "Practice questions",
                "studyGoal", "EXAM_PREPARATION", "studyMode", "ONLINE", "maxSize", "4",
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00",
                "slots[1].dayOfWeek", "WEDNESDAY", "slots[1].startTime", "14:00", "slots[1].endTime", "16:00");

        assertThat(leader.url()).matches("/groups/\\d+\\?from=mine");
        assertThat(leader.text()).contains("Group created. Other students can now find it on Study groups and ask to join.",
                name, "Practice questions", "Exam preparation", "Online", "Lena Leader", "1/4 members", ", places left",
                "Mon 10:00-12:00", "Wed 14:00-16:00", "You lead this group.", "Edit group", "Manage members");

        Browser other = signUp("Otto Other");
        other.open("/groups");
        assertThat(other.text()).contains(name);
    }

    // L2, GRP-1, GRP-7: the create form stops at 3 slots and lists every problem at once, then accepts touching slots (MAN-32)
    @Test
    void createFormListsEveryProblemThenAcceptsTouchingSlots() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Form Checker");
        String[] form = {"name", "", "courseId", String.valueOf(courseId), "description", "", "studyGoal", "EXAM_PREPARATION",
                "studyMode", "ONLINE", "maxSize", "1",
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00",
                "slots[1].dayOfWeek", "MONDAY", "slots[1].startTime", "11:00", "slots[1].endTime", "13:00",
                "slots[2].dayOfWeek", "TUESDAY", "slots[2].startTime", "12:00", "slots[2].endTime", "10:00"};

        leader.open("/groups/new");
        leader.submit("/groups", with(form, "addSlot", "true"));
        assertThat(leader.text()).contains("Max time slots reached (3)").doesNotContain("+ Add slot");

        leader.submit("/groups", form);
        assertThat(leader.url()).isEqualTo("/groups");
        assertThat(leader.text()).contains("Some details need fixing. See the messages below.",
                "Please enter a group name", "A group needs room for at least 2 members, including you",
                "Slot 3: the end time must be after the start time", "Slots 1 and 2 overlap");

        String name = "Form Check " + unique();
        leader.submit("/groups", "name", name, "courseId", String.valueOf(courseId), "studyGoal", "EXAM_PREPARATION",
                "studyMode", "ONLINE", "maxSize", "3",
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00",
                "slots[1].dayOfWeek", "MONDAY", "slots[1].startTime", "12:00", "slots[1].endTime", "13:00",
                "slots[2].dayOfWeek", "TUESDAY", "slots[2].startTime", "10:00", "slots[2].endTime", "12:00");
        assertThat(leader.text()).contains("Group created.", "Mon 10:00-12:00", "Mon 12:00-13:00", "Tue 10:00-12:00");
    }

    // L3, GRP-3: the leader edits the description; a size below the member count is refused and nothing is saved (MAN-13)
    @Test
    void leaderEditsAGroupButCannotShrinkItBelowItsMembers() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Edna Editor");
        long groupId = createGroup(leader, "Edit Group " + unique(), courseId, 4);
        for (String name : new String[] {"Mia Member", "Max Member"}) {
            askToJoin(signUp(name), groupId);
            answer(leader, groupId, name, "accept");
        }
        String[] edit = {"name", "Edit Group", "courseId", String.valueOf(courseId), "description", "Edited",
                "studyGoal", "EXAM_PREPARATION", "studyMode", "ONLINE", "maxSize", "4", "from", "mine",
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00"};

        leader.open("/groups/" + groupId + "/edit?from=mine");
        leader.submit("/groups/" + groupId + "/edit", edit);
        assertThat(leader.text()).contains("Changes saved.", "Edited", "3/4 members");

        leader.open("/groups/" + groupId + "/edit?from=mine");
        leader.submit("/groups/" + groupId + "/edit", replace(edit, "maxSize", "2"));
        assertThat(leader.text()).contains("Some details need fixing. See the messages below.",
                "The group has 3 members, so the maximum size can't be below 3");
        leader.open("/groups/" + groupId + "?from=mine");
        assertThat(leader.text()).contains("3/4 members");
    }

    // L3, GRP-7: on the edit form the leader adds a slot and removes one before saving; nothing is saved until Save changes
    @Test
    void leaderAddsAndRemovesSlotsOnTheEditForm() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Sol Slots");
        long groupId = createGroup(leader, "Slot Group " + unique(), courseId, 4);
        String[] edit = {"name", "Slot Group", "courseId", String.valueOf(courseId), "description", "",
                "studyGoal", "EXAM_PREPARATION", "studyMode", "ONLINE", "maxSize", "4",
                "slots[0].dayOfWeek", "MONDAY", "slots[0].startTime", "10:00", "slots[0].endTime", "12:00"};

        leader.open("/groups/" + groupId + "/edit");
        leader.submit("/groups/" + groupId + "/edit", with(edit, "addSlot", "true"));
        assertThat(leader.text()).contains("Edit group", "Slot 1", "Slot 2", "Remove");
        leader.submit("/groups/" + groupId + "/edit", with(edit, "slots[1].dayOfWeek", "FRIDAY",
                "slots[1].startTime", "09:00", "slots[1].endTime", "10:00", "removeSlot", "0"));
        assertThat(leader.text()).contains("Slot 1").doesNotContain("Slot 2");
        assertThat(leader.html()).contains("value=\"FRIDAY\" selected");

        leader.open("/groups/" + groupId);
        assertThat(leader.text()).contains("Mon 10:00-12:00").doesNotContain("Fri 09:00-10:00");
    }

    // L3, GRP-6, TD-4: closing ends the pending requests and freezes the group; it can then only be removed from My groups (MAN-34, MAN-37)
    @Test
    void closingEndsPendingRequestsAndFreezesTheGroup() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Cleo Closer");
        String name = "Close Group " + unique();
        long groupId = createGroup(leader, name, courseId, 4);
        Browser waiting = signUp("Wes Waiting");
        askToJoin(waiting, groupId);
        Browser waiting2 = signUp("Wendy Waiting");
        askToJoin(waiting2, groupId);

        leader.open("/groups/" + groupId + "?from=mine");
        assertThat(leader.text()).contains("Close this group? Its 2 pending requests will end without an answer, and you can't reopen it.");
        leader.submit("/groups/" + groupId + "/close", "from", "mine");
        assertThat(leader.text()).contains("Group closed. 2 pending requests ended without an answer.",
                "This group is closed, so it isn't taking new members.").doesNotContain("Edit group", "Manage members");

        leader.open("/groups/" + groupId + "/edit");
        assertThat(leader.url()).startsWith("/groups/" + groupId);
        assertThat(leader.text()).contains("This group is closed, so it can't be edited.");
        leader.open("/groups/" + groupId + "/members");
        assertThat(leader.text()).contains("This group is closed, so its members can't be changed.")
                .doesNotContain("Join requests", "Remove member");

        waiting.open("/groups/mine");
        String card = waiting.sectionAfter("Pending group join requests", "Declined or removed");
        assertThat(card).contains(name, "Closed", "The group was closed before the leader answered your request.");
        waiting.submit("/groups/" + groupId + "/remove-from-my-groups");
        assertThat(waiting.text()).contains("Removed from My groups.").doesNotContain(name);
        // Removing only hides it for that student
        waiting2.open("/groups/mine");
        assertThat(waiting2.text()).contains(name, "The group was closed before the leader answered your request.");

        leader.open("/groups/mine");
        leader.submit("/groups/" + groupId + "/remove-from-my-groups");
        assertThat(leader.text()).contains("Removed from My groups.").doesNotContain(name);
    }

    // L4, L5, GRP-2, GRP-4, GRP-5: students ask to join; the leader sees both requests, accepts one and rejects the other (MAN-14, MAN-31)
    @Test
    void leaderAcceptsAndRejectsJoinRequests() {
        Browser admin = newAdmin();
        String code = "GRP" + unique();
        long courseId = createCourse(admin, code);
        Browser leader = signUp("Rhea Reviewer");
        String name = "Join Group " + unique();
        long groupId = createGroup(leader, name, courseId, 4);
        Browser farah = signUp("Farah Joiner");
        takeCourse(farah, courseId);
        askToJoin(farah, groupId);
        assertThat(farah.text()).contains("Request sent. Waiting for the group's leader to respond.", "Cancel request");
        farah.open("/groups/mine");
        assertThat(farah.sectionAfter("Pending group join requests", "Declined or removed")).contains(name);
        Browser jas = signUp("Jas Joiner");
        askToJoin(jas, groupId);

        leader.open("/groups/mine");
        assertThat(leader.text()).contains("2 requests waiting");
        leader.open("/groups/" + groupId + "/members");
        assertThat(leader.sectionAfter("Join requests", "id=\"members-heading\"")).contains("Farah Joiner", "Jas Joiner",
                "Takes " + code);

        answer(leader, groupId, "Farah Joiner", "accept");
        assertThat(leader.text()).contains("Farah Joiner is now a member of the group.", "2/4 members");
        answer(leader, groupId, "Jas Joiner", "reject");
        assertThat(leader.text()).contains("Jas Joiner's request was rejected.", "No requests waiting.");

        farah.open("/groups/mine");
        assertThat(farah.sectionAfter("Joined groups", "Pending group join requests")).contains(name, "Led by Rhea Reviewer");
        jas.open("/groups/mine");
        assertThat(jas.sectionAfter("Declined or removed", "</main>")).contains(name, "Declined on");
        jas.open("/groups/" + groupId);
        assertThat(jas.text()).contains("Your earlier request to join this group was declined.", "Request to join again");
    }

    // L4: My groups counts one waiting request in the singular and none as "No requests waiting"
    @Test
    void myGroupsCountsWaitingRequests() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Cal Counter");
        long groupId = createGroup(leader, "Count Group " + unique(), courseId, 4);
        leader.open("/groups/mine");
        assertThat(leader.text()).contains("No requests waiting");

        askToJoin(signUp("One Waiting"), groupId);
        leader.open("/groups/mine");
        assertThat(leader.text()).contains("1 request waiting").doesNotContain("1 requests waiting");
    }

    // L6, GRP-4, GRP-5: the leader removes a member (never themselves); the removed student may ask to join again (MAN-15, MAN-35)
    @Test
    void leaderRemovesAMemberWhoMayAskAgain() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Remy Remover");
        String name = "Remove Group " + unique();
        long groupId = createGroup(leader, name, courseId, 4);
        Browser chloe = signUp("Chloe Removed");
        askToJoin(chloe, groupId);
        answer(leader, groupId, "Chloe Removed", "accept");

        answer(leader, groupId, "Chloe Removed", "remove");
        assertThat(leader.text()).contains("Chloe Removed was removed from the group.", "1/4 members");
        assertThat(leader.sectionAfter("id=\"members-heading\"", "</section>")).doesNotContain("Chloe Removed", "/remove");

        chloe.open("/groups/mine");
        assertThat(chloe.sectionAfter("Declined or removed", "</main>")).contains(name, "Removed from the group");
        chloe.open("/groups/" + groupId);
        assertThat(chloe.text()).contains("You were removed from this group before.", "Request to join again");
        chloe.submit("/groups/" + groupId + "/join");
        assertThat(chloe.text()).contains("Request sent. The group's leader will review it.");

        leader.open("/groups/" + groupId + "/members");
        assertThat(leader.sectionAfter("Join requests", "id=\"members-heading\"")).contains("Chloe Removed");
    }

    // L5, GRP-4, GRP-5, GRP-9, TD-3: a full group keeps a request waiting; Accept is off until a member leaves (MAN-36, MAN-31)
    @Test
    void fullGroupKeepsARequestWaitingUntilAPlaceOpens() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Fay Full");
        long groupId = createGroup(leader, "Full Group " + unique(), courseId, 2);
        Browser first = signUp("Ivy First");
        askToJoin(first, groupId);
        Browser second = signUp("Jo Second");
        askToJoin(second, groupId);

        answer(leader, groupId, "Ivy First", "accept");
        assertThat(leader.text()).contains("2/2 members", "Group is full. Remove a member to accept new members.");
        String waitingCard = leader.sectionAfter(">Jo Second<", "</li>");
        assertThat(waitingCard).contains("disabled").doesNotContain("/accept\"");

        second.open("/groups/" + groupId);
        assertThat(second.text()).contains("Request sent. Waiting for the group's leader to respond.",
                "The group is full right now. The leader can still accept you if a place opens up.");
        Browser late = signUp("Lou Late");
        late.open("/groups/" + groupId);
        assertThat(late.text()).contains("This group is full.");
        assertThat(late.html()).doesNotContain("/groups/" + groupId + "/join");

        first.open("/groups/" + groupId);
        first.submit("/groups/" + groupId + "/leave", "reason", "");
        answer(leader, groupId, "Jo Second", "accept");
        assertThat(leader.text()).contains("Jo Second is now a member of the group.", "2/2 members");
    }

    // L3, L4, GRP-2: a member sees no leader buttons, and typing the leader's addresses is refused (MAN-33)
    @Test
    void memberCannotManageOrEditTheGroup() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Lee Leader");
        String name = "Member Group " + unique();
        long groupId = createGroup(leader, name, courseId, 4);
        Browser member = signUp("Ann Member");
        askToJoin(member, groupId);
        answer(leader, groupId, "Ann Member", "accept");

        member.open("/groups/" + groupId + "?from=mine");
        assertThat(member.text()).contains("You're a member of this group.", "Leave group")
                .doesNotContain("Edit group", "Manage members", "Close group");

        member.open("/groups/" + groupId + "/members");
        assertThat(member.url()).startsWith("/groups/" + groupId);
        assertThat(member.text()).contains(name, "Only the group's leader can do that.");
        member.open("/groups/" + groupId + "/edit");
        assertThat(member.text()).contains("Only the group's leader can do that.");
        member.submit("/groups/" + groupId + "/close");
        assertThat(member.text()).contains("Only the group's leader can do that.");
    }

    // L1, GRP-4, TD-1: a student who doesn't take the course may lead a group for it and join one (MAN-38)
    @Test
    void groupsDoNotNeedTheCourse() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Sam Senior");
        long groupId = createGroup(leader, "Senior Group " + unique(), courseId, 3);
        leader.open("/student/profile");
        assertThat(leader.text()).contains("You haven't added any courses yet.");

        Browser joiner = signUp("Jay Junior");
        askToJoin(joiner, groupId);
    }

    // L4, GRP-4, GRP-8: a student cancels a waiting request from My groups; the leader can't answer it any more, and the student may ask again (MAN-47)
    @Test
    void studentCancelsAWaitingRequestAndMayAskAgain() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Kim Keeper");
        String name = "Cancel Group " + unique();
        long groupId = createGroup(leader, name, courseId, 4);
        Browser farah = signUp("Farah Cancel");
        askToJoin(farah, groupId);
        leader.open("/groups/" + groupId + "/members");
        long membershipId = leader.idAfter(">Farah Cancel<", "/members/(\\d+)/accept");

        farah.open("/groups/mine");
        assertThat(farah.text()).contains("Cancel your request to join " + name
                + "? If you ask again later, it will count as a new request.");
        farah.submit("/groups/" + groupId + "/cancel-request", "backToMyGroups", "true");
        assertThat(farah.url()).isEqualTo("/groups/mine");
        assertThat(farah.text()).contains("Request cancelled. You can ask to join again later.").doesNotContain(name);

        farah.open("/groups/" + groupId);
        assertThat(farah.text()).contains("Request to join").doesNotContain("declined", "removed", "Request to join again");

        leader.submit("/groups/" + groupId + "/members/" + membershipId + "/accept");
        assertThat(leader.text()).contains("The student has cancelled this request", "No requests waiting.");

        askToJoin(farah, groupId);
        assertThat(farah.text()).contains("Request sent. Waiting for the group's leader to respond.", "Cancel request");
    }

    // L4, GRP-2, GRP-4, GRP-9: a member leaves with a reason that only the leader sees; the leader can't leave (MAN-48)
    @Test
    void memberLeavesWithAReasonOnlyTheLeaderSees() {
        Browser admin = newAdmin();
        long courseId = newCourse(admin);
        Browser leader = signUp("Lia Leader");
        String name = "Leave Group " + unique();
        long groupId = createGroup(leader, name, courseId, 4);
        Browser ben = signUp("Ben Leaver");
        Browser stay = signUp("Stu Stayer");
        for (Browser student : new Browser[] {ben, stay}) {
            askToJoin(student, groupId);
        }
        answer(leader, groupId, "Ben Leaver", "accept");
        answer(leader, groupId, "Stu Stayer", "accept");

        ben.open("/groups/" + groupId + "?from=mine");
        assertThat(ben.text()).contains("Leave " + name + "? You can ask to join again later.",
                "Reason (optional, only the leader sees it)");
        ben.submit("/groups/" + groupId + "/leave", "reason", "x".repeat(501), "from", "mine");
        assertThat(ben.text()).contains("The reason can be at most 500 characters", "You're a member of this group.");
        ben.submit("/groups/" + groupId + "/leave", "reason", "  Exams clash with my job  ", "from", "mine");
        assertThat(ben.text()).contains("You left the group. You can ask to join again later.", "Request to join");
        ben.open("/groups/mine");
        assertThat(ben.text()).doesNotContain(name);

        leader.open("/groups/" + groupId + "/members");
        // The leader and Stu are left; Ben's place is free again
        assertThat(leader.text()).contains("2/4 members");
        assertThat(leader.sectionAfter("id=\"members-heading\"", "Members who left")).doesNotContain("Ben Leaver");
        assertThat(leader.sectionAfter("Members who left", "</main>")).contains("Ben Leaver", "Left on",
                "Reason:", "Exams clash with my job");

        stay.open("/groups/" + groupId);
        assertThat(stay.html()).doesNotContain("Exams clash with my job");

        leader.open("/groups/" + groupId + "?from=mine");
        assertThat(leader.text()).doesNotContain("Leave group");
        leader.submit("/groups/" + groupId + "/leave", "reason", "");
        assertThat(leader.text()).contains("The leader can't leave their own group");
    }

    private static String[] with(String[] fields, String... more) {
        String[] all = java.util.Arrays.copyOf(fields, fields.length + more.length);
        System.arraycopy(more, 0, all, fields.length, more.length);
        return all;
    }

    private static String[] replace(String[] fields, String name, String value) {
        String[] copy = fields.clone();
        for (int i = 0; i < copy.length; i += 2) {
            if (copy[i].equals(name)) {
                copy[i + 1] = value;
            }
        }
        return copy;
    }
}
