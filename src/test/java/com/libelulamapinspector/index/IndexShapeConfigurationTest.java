package com.libelulamapinspector.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexShapeConfigurationTest {

    @Test
    void calculatesExpectedInsertionsForDefaultShape() {
        IndexShapeConfiguration configuration = new IndexShapeConfiguration(100, 0.01D);

        assertEquals(100L * 1024L * 1024L, configuration.bytes());
        assertEquals(87_517_547L, configuration.expectedInsertions());
    }

    @Test
    void matchesOnlyIdenticalShapes() {
        IndexShapeConfiguration left = new IndexShapeConfiguration(100, 0.01D);

        assertTrue(left.matches(new IndexShapeConfiguration(100, 0.01D)));
        assertFalse(left.matches(new IndexShapeConfiguration(200, 0.01D)));
        assertFalse(left.matches(new IndexShapeConfiguration(100, 0.001D)));
    }
}
