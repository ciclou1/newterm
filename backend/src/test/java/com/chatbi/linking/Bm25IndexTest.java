package com.chatbi.linking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25IndexTest {

    private final Bm25Index index = new Bm25Index(new TextTokenizer());

    private final List<Bm25Index.Document<String>> documents = List.of(
            new Bm25Index.Document<>("sales", "sales", "销售订单 销售额 区域 客户"),
            new Bm25Index.Document<>("purchase", "purchase", "采购订单 采购金额 供应商"),
            new Bm25Index.Document<>("maintenance", "maintenance", "设备维修 维修成本 维修工单 工单数量")
    );

    @Test
    void ranksTheDocumentSharingTheMostQueryTermsFirst() {
        List<Bm25Index.Scored<String>> results = index.search("维修成本", documents, 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().id()).isEqualTo("maintenance");
    }

    @Test
    void returnsOnlyDocumentsWithAtLeastOneMatchingTerm() {
        List<Bm25Index.Scored<String>> results = index.search("库存周转率", documents, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void respectsTheLimitAndReturnsEmptyForBlankQueries() {
        assertThat(index.search("订单", documents, 1)).hasSize(1);
        assertThat(index.search("   ", documents, 5)).isEmpty();
        assertThat(index.search("订单", List.of(), 5)).isEmpty();
    }
}
