package mocks

import horus.events.EventKey

data class MockTaskEvent(val somePayload: String) {
    companion object : EventKey<MockTaskEvent>
}
