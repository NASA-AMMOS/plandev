"""
Telecom model.

Port of ``missionmodel.telecom.TelecomModel`` and the standalone link-budget
helper in ``missionmodel.telecom.LinkModel``.  The full Java telecom model is
mostly commented out (DSN antennae, pass generation, etc.); the active part is a
single ``downlinkBitRate`` resource plus a static link-equation calculator,
both reproduced here.
"""

import math

#: Boltzmann constant (J/K).
BOLTZMANN_CONSTANT = 1.380649e-23


class TelecomModel:
    def __init__(self, registrar):
        # Actually-occurring downlink bit rate (bps); set by the Downlink activity.
        self.downlink_bit_rate = registrar.cell(0.0)

    def register_resources(self, registrar):
        registrar.resource("downlinkBitRate", self.downlink_bit_rate)


def space_loss(wavelength_m: float, distance_m: float) -> float:
    """Free-space path loss factor (unitless):  (lambda / (4 pi d))^2."""
    ratio = wavelength_m / (4.0 * math.pi * distance_m)
    return ratio * ratio


def get_bit_rate(power_input_w,
                 communication_system_efficiency,
                 transmitting_antenna_gain,
                 space_loss_factor,
                 atmospheric_loss,
                 pointing_error_loss,
                 receiving_antenna_gain,
                 system_temperature_k,
                 desired_signal_to_noise_ratio) -> float:
    """
    Port of ``LinkModel.getBitRate`` (assuming bandwidth ~ bit rate), returning
    Mbps.  All loss/gain/efficiency arguments are unitless scalars.
    """
    numerator = (power_input_w
                 * communication_system_efficiency
                 * transmitting_antenna_gain
                 * space_loss_factor
                 * atmospheric_loss
                 * pointing_error_loss
                 * receiving_antenna_gain)
    denominator = (BOLTZMANN_CONSTANT
                   * system_temperature_k
                   * desired_signal_to_noise_ratio)
    bandwidth = numerator / denominator  # ~ bits per second
    return bandwidth / 1e6               # -> Mbps
