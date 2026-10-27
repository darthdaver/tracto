package star.tracto.utils.javaParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the allocation of the names of generated variables and labels ({@link NameAllocator}).
 */
class NameAllocatorTest {

    @Test
    void unusedNameIsAllocatedAsItIs() {
        NameAllocator names = new NameAllocator();

        assertEquals("count", names.allocate("count"));
        assertTrue(names.isDeclared("count"));
    }

    @Test
    void usedNameGetsTheFirstFreeNumericSuffixStartingFromTwo() {
        NameAllocator names = new NameAllocator();
        names.declare("count");
        names.declare("count3");

        assertEquals(List.of("count2", "count4", "count5"),
                List.of(names.allocate("count"), names.allocate("count"), names.allocate("count")));
    }

    @Test
    void reservedNameIsNotAllocatedAndIsNotDeclared() {
        NameAllocator names = new NameAllocator();
        names.reserve("base");

        assertEquals("base2", names.allocate("base"));
        assertTrue(names.isUsed("base"));
        assertFalse(names.isDeclared("base"));
        assertTrue(names.isDeclared("base2"));
    }

    @Test
    void unknownNameIsNeitherUsedNorDeclared() {
        NameAllocator names = new NameAllocator();

        assertFalse(names.isUsed("x"));
        assertFalse(names.isDeclared("x"));
    }
}
