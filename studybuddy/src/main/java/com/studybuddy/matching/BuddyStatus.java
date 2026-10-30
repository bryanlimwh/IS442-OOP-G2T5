package com.studybuddy.matching;

/**
 * How the searcher is already linked to a student on the matches page. Such a student is still
 * listed (team decision TD-5), but their card shows a label instead of the "Send request" button.
 * A student with no link, or only a rejected request, has no status and keeps the button.
 */
public enum BuddyStatus {
    CONNECTED,
    REQUEST_SENT,
    REQUEST_RECEIVED
}
