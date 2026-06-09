package com.hmdp.dto;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Size;

@Data
public class ShopCreateDTO {

    @NotBlank(message = "name is required")
    @Size(max = 128, message = "name length must be less than or equal to 128")
    private String name;

    @NotNull(message = "typeId is required")
    @Positive(message = "typeId must be greater than 0")
    private Long typeId;

    @NotBlank(message = "images is required")
    @Size(max = 1024, message = "images length must be less than or equal to 1024")
    private String images;

    @Size(max = 128, message = "area length must be less than or equal to 128")
    private String area;

    @NotBlank(message = "address is required")
    @Size(max = 255, message = "address length must be less than or equal to 255")
    private String address;

    @NotNull(message = "x is required")
    @DecimalMin(value = "-180.0", message = "x must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "x must be between -180 and 180")
    private Double x;

    @NotNull(message = "y is required")
    @DecimalMin(value = "-90.0", message = "y must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "y must be between -90 and 90")
    private Double y;

    @PositiveOrZero(message = "avgPrice must be greater than or equal to 0")
    private Long avgPrice;

    @Size(max = 32, message = "openHours length must be less than or equal to 32")
    private String openHours;

    @AssertTrue(message = "x and y must be provided together")
    public boolean isCoordinatePairValid() {
        return (x == null) == (y == null);
    }
}
