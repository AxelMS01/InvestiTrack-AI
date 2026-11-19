package com.example.proposalservice.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CursorPagesTests {
    @Test void duplicateSortValuesUseIdTieBreakerWithoutRepeatingItems() {
        List<Map<String,Object>> rows=List.of(Map.of("id","b","title","Same"),Map.of("id","a","title","Same"),Map.of("id","c","title","Other"));
        var first=CursorPages.page(rows,1,null,"-title",Set.of("title"));
        assertEquals("a",first.items().getFirst().get("id"));
        var second=CursorPages.page(rows,1,first.page().nextCursor(),"-title",Set.of("title"));
        assertEquals("b",second.items().getFirst().get("id"));
        var third=CursorPages.page(rows,1,second.page().nextCursor(),"-title",Set.of("title"));
        assertEquals("c",third.items().getFirst().get("id")); assertFalse(third.page().hasMore());
    }
    @Test void malformedCursorsLimitsAndUnsupportedSortingFail() {
        assertThrows(ApiException.class,()->CursorPages.page(List.of(),0,null,"id",Set.of("id")));
        assertThrows(ApiException.class,()->CursorPages.page(List.of(),25,"invalid","id",Set.of("id")));
        assertThrows(ApiException.class,()->CursorPages.page(List.of(),25,null,"secret",Set.of("id")));
    }
    @Test void terminalStatesCannotTransition() {
        for(var status:List.of(com.example.proposalservice.infraestructure.persistence.ProposalStatus.APPROVED,com.example.proposalservice.infraestructure.persistence.ProposalStatus.REJECTED,com.example.proposalservice.infraestructure.persistence.ProposalStatus.CANCELLED))
            for(var target:com.example.proposalservice.infraestructure.persistence.ProposalStatus.values())
                assertFalse(com.example.proposalservice.application.ProposalApplicationService.allowed(status,target));
    }
}
