package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.VoucherRepository;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-user redemption limit. Each test runs in a transaction that is rolled
 * back afterwards, so nothing here leaks into the shared seed data that
 * {@link VoucherServiceTest} relies on. RAYA-0001 is deliberately not used
 * because that suite redeems it without cleanup.
 */
@SpringBootTest
@Transactional
class RedemptionLimitTest {

    private static final String LIMIT_MESSAGE = "User has reached the redemption limit for this campaign (2)";

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Test
    void existingCampaignsDefaultToLimitOfTwo() {
        for (Campaign campaign : campaignRepository.findAll()) {
            assertEquals(2, campaign.getMaxRedemptionsPerUser(), "campaign " + campaign.getId());
        }
    }

    @Test
    void userAtLimitIsRejectedOnCampaign1() {
        // user-99 is seeded with two redemption rows on campaign 1
        RedeemResponse res = voucherService.redeem("RAYA-0002", "user-99");

        assertEquals("FAILED", res.getResult());
        assertEquals(LIMIT_MESSAGE, res.getMessage());
    }

    @Test
    void newUserCanRedeemTwiceThenIsRejectedOnThird() {
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-5").getResult());
        assertEquals("OK", voucherService.redeem("RAYA-0003", "user-5").getResult());

        RedeemResponse third = voucherService.redeem("RAYA-0006", "user-5");
        assertEquals("FAILED", third.getResult());
        assertEquals(LIMIT_MESSAGE, third.getMessage());
    }

    @Test
    void userAtLimitOnCampaign1CanStillRedeemOnCampaign2() {
        RedeemResponse res = voucherService.redeem("MRDK-0001", "user-99");

        assertEquals("OK", res.getResult());
        assertEquals("MRDK-0001", res.getVoucherCode());
    }

    @Test
    void rejectedRedemptionDoesNotTouchVoucherOrStock() {
        int stockBefore = campaignRepository.findById(1L).get().getRemainingStock();

        RedeemResponse res = voucherService.redeem("RAYA-0002", "user-99");
        assertEquals("FAILED", res.getResult());

        assertEquals(stockBefore, campaignRepository.findById(1L).get().getRemainingStock());
        assertEquals("ACTIVE", voucherRepository.findByCode("RAYA-0002").getStatus());
        assertTrue(voucherRepository.findByCode("RAYA-0002").getRedeemedBy() == null);
    }
}
