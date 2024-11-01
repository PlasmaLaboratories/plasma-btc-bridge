// StartSession.test.tsx
import { render, screen } from "@testing-library/react";
import { PeginUIState } from "../controllers/PeginController";
import StartSession from "./StartSession";
import '@testing-library/jest-dom'

test("renders StartSession button when session is not set", () => {
  render(<StartSession
    isSet={false}
    sessionID="abc"
    escrowAddress=""
    escrowScript=""
    currentState={PeginUIState.InitialState}
    redeemAddress=""
    plasmaBridgePKey=""
    redeemTemplate="" />);
  // verify that the button is present
  const buttonElement = screen.getByText(/Start Session/i);
  expect(buttonElement).toBeInTheDocument();
})

// to render disabled button when session is not set
test("renders disabled StartSession button when session is set", () => {
  render(<StartSession
    isSet={true}
    sessionID="abc"
    escrowAddress=""
    escrowScript=""
    currentState={PeginUIState.InitialState}
    redeemAddress=""
    plasmaBridgePKey=""
    redeemTemplate="" />);
  // verify that the button is present
  const buttonElement = screen.getByText(/Start Session/i);
  expect(buttonElement).toBeDisabled();
})