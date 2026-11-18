package com.instacommerce.featureflag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.domain.model.Experiment;
import com.instacommerce.featureflag.domain.model.ExperimentVariant;
import com.instacommerce.featureflag.dto.request.ExperimentVariantRequest;
import com.instacommerce.featureflag.dto.request.UpdateExperimentRequest;
import com.instacommerce.featureflag.dto.response.ExperimentResponse;
import com.instacommerce.featureflag.repository.ExperimentRepository;
import com.instacommerce.featureflag.repository.ExperimentVariantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExperimentManagementServiceTest {

    @Mock
    private ExperimentRepository experimentRepository;

    @Mock
    private ExperimentVariantRepository variantRepository;

    private ExperimentManagementService service;

    @BeforeEach
    void setUp() {
        service = new ExperimentManagementService(experimentRepository, variantRepository, new ObjectMapper());
    }

    @Test
    void updateExperiment_reconcilesVariantsInPlaceAndFlushesRemovals() {
        UUID experimentId = UUID.randomUUID();
        UUID controlId = UUID.randomUUID();
        UUID legacyId = UUID.randomUUID();

        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setKey("exp-pricing");

        ExperimentVariant existingControl = new ExperimentVariant();
        existingControl.setId(controlId);
        existingControl.setExperimentId(experimentId);
        existingControl.setName("control");
        existingControl.setWeight(50);
        existingControl.setControl(true);

        ExperimentVariant obsoleteVariant = new ExperimentVariant();
        obsoleteVariant.setId(legacyId);
        obsoleteVariant.setExperimentId(experimentId);
        obsoleteVariant.setName("old-treatment");
        obsoleteVariant.setWeight(50);

        when(experimentRepository.findByKey("exp-pricing")).thenReturn(Optional.of(experiment));
        when(experimentRepository.save(any(Experiment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(variantRepository.findByExperimentIdOrderByName(experimentId))
                .thenReturn(List.of(existingControl, obsoleteVariant));
        when(variantRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        UpdateExperimentRequest request = new UpdateExperimentRequest(
                "Updated Pricing", null, null, null, null, null,
                null, null, null, null, null,
                List.of(
                        new ExperimentVariantRequest("control", 60, true, "{\"fee\":0}"),
                        new ExperimentVariantRequest("treatment-b", 40, false, "{\"fee\":10}")));

        ExperimentResponse response = service.updateExperiment("exp-pricing", request, "admin@instacommerce.dev");

        verify(variantRepository).deleteAll(List.of(obsoleteVariant));
        verify(variantRepository).flush();

        ArgumentCaptor<List<ExperimentVariant>> savedCaptor = ArgumentCaptor.captor();
        verify(variantRepository).saveAll(savedCaptor.capture());

        List<ExperimentVariant> saved = savedCaptor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getId()).isEqualTo(controlId);
        assertThat(saved.get(0).getWeight()).isEqualTo(60);
        assertThat(saved.get(1).getId()).isNull();
        assertThat(saved.get(1).getName()).isEqualTo("treatment-b");
        assertThat(response.variants()).hasSize(2);
    }
}
